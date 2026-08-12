package com.explorer.fileexplorer.core.data

import android.content.Context
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.storage.FileOperationStaging
import com.explorer.fileexplorer.core.storage.StagedEntry
import com.explorer.fileexplorer.core.storage.StagingManifest
import com.explorer.fileexplorer.core.storage.StagingRecoveryPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
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

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.local("local")

    override fun deleteCapabilities(paths: List<String>): DeleteCapabilities = DeleteCapabilities.LOCAL_BEST_EFFORT

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
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
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
        try {
            val destinationDirectory = Paths.get(destination).toFile().canonicalFile
            if (!destinationDirectory.isDirectory) {
                throw IOException("Copy destination is not a directory: $destination")
            }
            FileOperationStaging.recoverOrphaned(destinationDirectory)
            val totalSize = calculateSize(sources)
            var count = 0
            var transferred = 0L
            for (src in sources) {
                val srcPath = Paths.get(src)
                val requestedTarget = destinationDirectory.toPath().resolve(srcPath.fileName.toString())
                val target = resolveTransferTarget(requestedTarget, conflictResolution)
                val sourceSize = calculateSize(listOf(src))
                if (target == null) {
                    transferred += sourceSize
                    onProgress(transferred, totalSize, srcPath.fileName.toString())
                    count++
                    continue
                }
                val operation = stageAndCommitCopy(
                    source = srcPath,
                    target = target,
                    startBytes = transferred,
                    totalBytes = totalSize,
                    onProgress = onProgress,
                    moveSource = false,
                )
                FileOperationStaging.deleteRecursively(operation)
                transferred += sourceSize
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.COPY))
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
        try {
            val destinationDirectory = Paths.get(destination).toFile().canonicalFile
            if (!destinationDirectory.isDirectory) {
                throw IOException("Move destination is not a directory: $destination")
            }
            FileOperationStaging.recoverOrphaned(destinationDirectory)
            var count = 0
            for (src in sources) {
                val srcPath = Paths.get(src)
                val requestedTarget = destinationDirectory.toPath().resolve(srcPath.fileName.toString())
                val sourceSize = calculateSize(listOf(src))
                if (requestedTarget.normalize() == srcPath.normalize()) {
                    onProgress(sourceSize, sourceSize, srcPath.fileName.toString())
                    count++
                    continue
                }
                val target = resolveTransferTarget(requestedTarget, conflictResolution)
                if (target == null) {
                    onProgress(sourceSize, sourceSize, srcPath.fileName.toString())
                    count++
                    continue
                }
                val operation = if (target.parent == srcPath.parent && !Files.exists(target)) {
                    stageAndCommitCopy(
                        source = srcPath,
                        target = target,
                        startBytes = 0L,
                        totalBytes = sourceSize,
                        onProgress = onProgress,
                        moveSource = true,
                        stageByMove = true,
                    )
                } else {
                    stageAndCommitCopy(
                        source = srcPath,
                        target = target,
                        startBytes = 0L,
                        totalBytes = sourceSize,
                        onProgress = onProgress,
                        moveSource = true,
                    )
                }
                FileOperationStaging.deleteRecursively(operation)
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (path in paths) {
                val p = Paths.get(path)
                if (!Files.exists(p)) {
                    count++
                    continue
                }
                val parent = p.parent?.toFile() ?: throw IOException("Cannot stage filesystem root: $path")
                FileOperationStaging.recoverOrphaned(parent)
                val operation = FileOperationStaging.createOperation(
                    baseDirectory = parent,
                    operation = "delete",
                    recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                )
                val staged = operation.toPath().resolve("payload").resolve(p.fileName.toString()).toFile()
                staged.parentFile?.mkdirs()
                FileOperationStaging.writeManifest(
                    operation,
                    StagingManifest(
                        operation = "delete",
                        recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                        entries = listOf(StagedEntry(p.toString(), staged.toString())),
                    ),
                )
                FileOperationStaging.atomicMove(p, staged.toPath(), replaceExisting = false)
                FileOperationStaging.writeManifest(
                    operation,
                    StagingManifest(
                        operation = "delete",
                        recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                        entries = listOf(StagedEntry(p.toString(), staged.toString(), state = FileOperationStaging.STATE_STAGED)),
                    ),
                )
                onProgress(p.fileName.toString())
                FileOperationStaging.writeManifest(
                    operation,
                    StagingManifest(
                        operation = "delete",
                        recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                        entries = listOf(StagedEntry(p.toString(), staged.toString(), state = FileOperationStaging.STATE_COMMITTED)),
                    ),
                )
                FileOperationStaging.deleteRecursively(staged)
                FileOperationStaging.deleteRecursively(operation)
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
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

    private fun resolveTransferTarget(target: Path, resolution: ConflictResolution): Path? = when {
        !Files.exists(target) -> target
        resolution == ConflictResolution.SKIP -> null
        resolution == ConflictResolution.RENAME || resolution == ConflictResolution.ASK -> resolveConflict(target)
        else -> target
    }

    private fun stageAndCommitCopy(
        source: Path,
        target: Path,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long, String) -> Unit,
        moveSource: Boolean,
        stageByMove: Boolean = false,
    ): File {
        val operation = FileOperationStaging.createOperation(
            baseDirectory = target.parent.toFile(),
            operation = if (moveSource) "move" else "copy",
            recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
        )
        val staged = operation.toPath().resolve("payload").resolve(target.fileName).toFile()
        val backup = operation.toPath().resolve("backup").resolve(target.fileName).toFile()
        val entries = mutableListOf(
            StagedEntry(
                originalPath = source.toString(),
                stagedPath = staged.toString(),
                committedPath = target.toString(),
            ),
        )
        if (Files.exists(target)) {
            if (!moveSource && source.normalize() == target.normalize()) {
                throw IOException("Cannot copy a file over itself")
            }
            entries += StagedEntry(
                originalPath = target.toString(),
                stagedPath = backup.toString(),
            )
        }
        fun manifest() = StagingManifest(
            operation = if (moveSource) "move" else "copy",
            recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
            entries = entries.toList(),
        )

        try {
            FileOperationStaging.writeManifest(operation, manifest())
            staged.parentFile?.mkdirs()
            if (stageByMove) {
                FileOperationStaging.atomicMove(source, staged.toPath(), replaceExisting = false)
            } else {
                copyRecursive(
                    source = source,
                    target = staged.toPath(),
                    resolution = ConflictResolution.OVERWRITE,
                    startBytes = startBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )
                forceTree(staged.toPath())
            }
            entries[0] = entries[0].copy(state = FileOperationStaging.STATE_STAGED)
            FileOperationStaging.writeManifest(operation, manifest())

            if (Files.exists(target)) {
                backup.parentFile?.mkdirs()
                FileOperationStaging.atomicMove(target, backup.toPath(), replaceExisting = false)
                entries[1] = entries[1].copy(state = FileOperationStaging.STATE_BACKUP_MOVED)
                FileOperationStaging.writeManifest(operation, manifest())
            }

            FileOperationStaging.atomicMove(staged.toPath(), target, replaceExisting = false)
            entries[0] = entries[0].copy(state = FileOperationStaging.STATE_TARGET_COMMITTED)
            FileOperationStaging.writeManifest(operation, manifest())
            if (moveSource && !stageByMove) {
                deleteRecursive(source)
            }
            if (stageByMove) onProgress(totalBytes, totalBytes, source.fileName.toString())
            entries.indices.forEach { index -> entries[index] = entries[index].copy(state = FileOperationStaging.STATE_COMMITTED) }
            FileOperationStaging.writeManifest(operation, manifest())
            entries.drop(1).forEach { entry -> FileOperationStaging.deleteRecursively(File(entry.stagedPath)) }
            return operation
        } catch (error: Exception) {
            // Leave the journal in place so the next repository operation can roll it back.
            throw error
        }
    }

    private fun forceTree(path: Path) {
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { stream -> stream.forEach { forceTree(it) } }
        } else {
            FileOperationStaging.forceFile(path.toFile())
        }
        FileOperationStaging.forceDirectory(path.toFile().parentFile ?: path.toFile())
    }

    private fun atomicMoveWithJournal(
        source: Path,
        target: Path,
        sourceSize: Long,
        onProgress: (Long, Long, String) -> Unit,
    ): File = stageAndCommitCopy(
        source = source,
        target = target,
        startBytes = 0L,
        totalBytes = sourceSize,
        onProgress = onProgress,
        moveSource = true,
        stageByMove = true,
    )

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val p = Paths.get(path)
            Files.createDirectories(p)
            Result.success(pathToFileItem(p))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY))
        }
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val p = Paths.get(path)
            Files.createFile(p)
            Result.success(pathToFileItem(p))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CREATE_FILE))
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val source = Paths.get(path)
            val target = source.resolveSibling(newName)
            Files.move(source, target)
            Result.success(pathToFileItem(target))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.RENAME))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = try {
        withContext(Dispatchers.IO) {
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
    } catch (e: Exception) {
        throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SIZE)
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
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
            } catch (e: Exception) {
                throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun searchStreaming(
        rootPath: String,
        query: String,
        regex: Boolean = false,
        includeHidden: Boolean = false,
    ): Flow<FileItem> = search(rootPath, query, regex, includeHidden)

    override suspend fun getChecksum(path: String, algorithm: String): String = try {
        withContext(Dispatchers.IO) {
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
    } catch (e: Exception) {
        throw e.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
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
