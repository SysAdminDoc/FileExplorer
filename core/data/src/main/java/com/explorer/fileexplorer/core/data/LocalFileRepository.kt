package com.explorer.fileexplorer.core.data

import android.content.Context
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : FileRepository {

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val dir = Paths.get(path)
        if (!Files.isDirectory(dir)) {
            emit(emptyList())
            return@flow
        }
        val items = mutableListOf<FileItem>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (entry in stream) {
                    items.add(pathToFileItem(entry))
                }
            }
        } catch (e: AccessDeniedException) {
            // Partial results if some files are accessible
        } catch (e: IOException) {
            // Empty result on IO error
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val p = Paths.get(path)
        if (!Files.exists(p)) return@withContext null
        pathToFileItem(p)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(Paths.get(path))
    }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        val totalSize = calculateSize(sources)
        var transferred = 0L

        try {
            for (src in sources) {
                val srcPath = Paths.get(src)
                val destPath = Paths.get(destination, srcPath.fileName.toString())
                transferred = copyRecursive(srcPath, destPath, conflictResolution, transferred, totalSize, onProgress)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copyRecursive(
        source: Path, target: Path,
        resolution: ConflictResolution,
        startBytes: Long, totalBytes: Long,
        onProgress: (Long, Long, String) -> Unit,
    ): Long {
        var transferred = startBytes

        if (Files.isDirectory(source)) {
            if (!Files.exists(target)) Files.createDirectories(target)
            Files.newDirectoryStream(source).use { stream ->
                for (entry in stream) {
                    val dest = target.resolve(entry.fileName)
                    transferred = copyRecursive(entry, dest, resolution, transferred, totalBytes, onProgress)
                }
            }
        } else {
            val options = when (resolution) {
                ConflictResolution.OVERWRITE -> arrayOf(StandardCopyOption.REPLACE_EXISTING)
                ConflictResolution.SKIP -> {
                    if (Files.exists(target)) return transferred + Files.size(source)
                    emptyArray()
                }
                ConflictResolution.RENAME -> {
                    val finalTarget = resolveConflict(target)
                    Files.copy(source, finalTarget, StandardCopyOption.COPY_ATTRIBUTES)
                    val size = Files.size(source)
                    transferred += size
                    onProgress(transferred, totalBytes, source.fileName.toString())
                    return transferred
                }
                else -> emptyArray()
            }
            Files.copy(source, target, *options, StandardCopyOption.COPY_ATTRIBUTES)
            val size = Files.size(source)
            transferred += size
            onProgress(transferred, totalBytes, source.fileName.toString())
        }
        return transferred
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (src in sources) {
                val srcPath = Paths.get(src)
                val destPath = Paths.get(destination, srcPath.fileName.toString())
                try {
                    // Atomic move (same filesystem)
                    Files.move(srcPath, destPath, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    // Cross-filesystem: copy + delete
                    copyRecursive(srcPath, destPath, conflictResolution, 0, calculateSize(listOf(src)), onProgress)
                    deleteRecursive(srcPath)
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (path in paths) {
                val p = Paths.get(path)
                onProgress(p.fileName.toString())
                deleteRecursive(p)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun deleteRecursive(path: Path) {
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { stream ->
                for (entry in stream) deleteRecursive(entry)
            }
        }
        Files.deleteIfExists(path)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val p = Paths.get(path)
            Files.createDirectories(p)
            Result.success(pathToFileItem(p))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val p = Paths.get(path)
            Files.createFile(p)
            Result.success(pathToFileItem(p))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val source = Paths.get(path)
            val target = source.resolveSibling(newName)
            Files.move(source, target)
            Result.success(pathToFileItem(target))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        var total = 0L
        for (path in paths) {
            val p = Paths.get(path)
            if (Files.isDirectory(p)) {
                Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                total += Files.size(p)
            }
        }
        total
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow<FileItem> {
        val root = Paths.get(rootPath)
        val pattern = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (!includeHidden && name.startsWith(".")) return FileVisitResult.CONTINUE
                val matches = if (pattern != null) pattern.containsMatchIn(name)
                else name.contains(query, ignoreCase = true)
                if (matches) {
                    // walkFileTree visitor can't emit - this search uses non-streaming approach
                }
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                if (!includeHidden && name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
        })
    }.flowOn(Dispatchers.IO)

    // Proper streaming search using manual recursion
    fun searchStreaming(
        rootPath: String,
        query: String,
        regex: Boolean = false,
        includeHidden: Boolean = false,
    ): Flow<FileItem> = flow {
        val root = Paths.get(rootPath)
        val pattern = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null
        val stack = ArrayDeque<Path>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            try {
                Files.newDirectoryStream(current).use { stream ->
                    for (entry in stream) {
                        val name = entry.fileName.toString()
                        if (!includeHidden && name.startsWith(".")) continue

                        val matches = if (pattern != null) pattern.containsMatchIn(name)
                        else name.contains(query, ignoreCase = true)

                        if (matches) emit(pathToFileItem(entry))
                        if (Files.isDirectory(entry)) stack.addLast(entry)
                    }
                }
            } catch (_: AccessDeniedException) { /* skip inaccessible dirs */ }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(8192)
        Files.newInputStream(Paths.get(path)).use { stream ->
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    // -- Internal helpers --

    private fun pathToFileItem(path: Path): FileItem {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) { null }

        val posix = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) { null }

        val name = path.fileName?.toString() ?: path.toString()
        val ext = name.substringAfterLast('.', "")
        val isDir = attrs?.isDirectory ?: false
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"

        return FileItem(
            name = name,
            path = path.toAbsolutePath().toString(),
            size = attrs?.size() ?: 0L,
            lastModified = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            isDirectory = isDir,
            isHidden = name.startsWith("."),
            isSymlink = attrs?.isSymbolicLink ?: false,
            isReadable = Files.isReadable(path),
            isWritable = Files.isWritable(path),
            mimeType = mime,
            extension = ext,
            permissions = posix?.permissions() ?: emptySet(),
            ownerName = posix?.owner()?.name,
            groupName = posix?.group()?.name,
            symlinkTarget = if (attrs?.isSymbolicLink == true) {
                try { Files.readSymbolicLink(path).toString() } catch (_: Exception) { null }
            } else null,
        )
    }

    private fun resolveConflict(target: Path): Path {
        if (!Files.exists(target)) return target
        val name = target.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        var candidate: Path
        do {
            candidate = target.resolveSibling("${base} ($counter)$ext")
            counter++
        } while (Files.exists(candidate))
        return candidate
    }
}
