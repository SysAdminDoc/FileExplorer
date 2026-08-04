package com.explorer.fileexplorer.core.data

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.storage.ShizukuManager
import com.explorer.fileexplorer.core.storage.ShizukuPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import javax.inject.Singleton

/** File operations for the Android/data subtree through a granted Shizuku UserService. */
@Singleton
class ShizukuFileRepository @Inject constructor(
    private val shizukuManager: ShizukuManager,
) : FileRepository {

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        if (!ShizukuPaths.isAllowed(path)) {
            emit(emptyList())
            return@flow
        }
        val result = shizukuManager.execute("ls -lAp -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) {
            emit(emptyList())
            return@flow
        }
        emit(
            result.output.lineSequence()
                .dropWhile { it.startsWith("total ") }
                .mapNotNull { parseLsLine(it, path) }
                .toList(),
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) return@withContext null
        val result = shizukuManager.execute("ls -ldA -- ${ShizukuPaths.shellQuote(path)}")
        result.output.lineSequence().mapNotNull { line ->
            parseLsLine(line, path.substringBeforeLast('/', ""))
        }.firstOrNull()
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        ShizukuPaths.isAllowed(path) &&
            shizukuManager.execute("test -e ${ShizukuPaths.shellQuote(path)}").isSuccess
    }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(destination) || sources.any { !ShizukuPaths.isAllowed(it) }) {
            return@withContext Result.failure(IllegalArgumentException("Shizuku only supports Android/data paths"))
        }
        var count = 0
        for (source in sources) {
            onProgress(0, 0, source.substringAfterLast('/'))
            val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-rf" else "-rn"
            if (shizukuManager.execute(
                    "cp $flags -- ${ShizukuPaths.shellQuote(source)} ${ShizukuPaths.shellQuote(destination)}/",
                ).isSuccess
            ) count++
        }
        Result.success(count)
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(destination) || sources.any { !ShizukuPaths.isAllowed(it) }) {
            return@withContext Result.failure(IllegalArgumentException("Shizuku only supports Android/data paths"))
        }
        var count = 0
        for (source in sources) {
            onProgress(0, 0, source.substringAfterLast('/'))
            val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-f" else "-n"
            if (shizukuManager.execute(
                    "mv $flags -- ${ShizukuPaths.shellQuote(source)} ${ShizukuPaths.shellQuote(destination)}/",
                ).isSuccess
            ) count++
        }
        Result.success(count)
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (paths.any { !ShizukuPaths.isAllowed(it) || ShizukuPaths.isProtectedRoot(it) }) {
            return@withContext Result.failure(IllegalArgumentException("Cannot delete a protected Android root"))
        }
        var count = 0
        for (path in paths) {
            onProgress(path.substringAfterLast('/'))
            if (shizukuManager.execute("rm -rf -- ${ShizukuPaths.shellQuote(path)}").isSuccess) count++
        }
        Result.success(count)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) return@withContext unsupported()
        val result = shizukuManager.execute("mkdir -p -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) Result.failure(Exception(result.output.ifBlank { "Unable to create directory" }))
        else getFileInfo(path)?.let(Result.Companion::success)
            ?: Result.failure(Exception("Created but cannot stat"))
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) return@withContext unsupported()
        val result = shizukuManager.execute("touch -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) Result.failure(Exception(result.output.ifBlank { "Unable to create file" }))
        else getFileInfo(path)?.let(Result.Companion::success)
            ?: Result.failure(Exception("Created but cannot stat"))
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val parent = path.substringBeforeLast('/', "")
        val target = ShizukuPaths.child(parent, newName)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Android/data filename"))
        val result = shizukuManager.execute(
            "mv -- ${ShizukuPaths.shellQuote(path)} ${ShizukuPaths.shellQuote(target)}",
        )
        if (!result.isSuccess) Result.failure(Exception(result.output.ifBlank { "Unable to rename" }))
        else getFileInfo(target)?.let(Result.Companion::success)
            ?: Result.failure(Exception("Renamed but cannot stat"))
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        paths.filter(ShizukuPaths::isAllowed).sumOf { path ->
            val result = shizukuManager.execute("du -sk -- ${ShizukuPaths.shellQuote(path)}")
            result.output.lineSequence().firstOrNull()?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024) ?: 0L
        }
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        if (!ShizukuPaths.isAllowed(rootPath)) return@flow
        val pattern = if (regex) ".*$query.*" else "*$query*"
        val hiddenFilter = if (!includeHidden) "! -name ${ShizukuPaths.shellQuote(".*")}" else ""
        val command = "find ${ShizukuPaths.shellQuote(rootPath)} -maxdepth 5 " +
            "${if (regex) "-regex" else "-iname"} ${ShizukuPaths.shellQuote(pattern)} $hiddenFilter -print"
        val result = shizukuManager.execute(command)
        if (result.isSuccess) {
            result.output.lineSequence().filter { it.isNotBlank() }.forEach { path ->
                getFileInfo(path.trim())?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) return@withContext ""
        val command = when (algorithm.uppercase()) {
            "MD5" -> "md5sum"
            "SHA-1", "SHA1" -> "sha1sum"
            "SHA-256", "SHA256" -> "sha256sum"
            "SHA-512", "SHA512" -> "sha512sum"
            else -> "sha256sum"
        }
        shizukuManager.execute("$command ${ShizukuPaths.shellQuote(path)}").output
            .lineSequence().firstOrNull()?.substringBefore(' ') ?: ""
    }

    private fun parseLsLine(line: String, parentPath: String): FileItem? {
        val parts = line.trim().split("\\s+".toRegex(), limit = 9)
        if (parts.size < 8 || parts[0].length < 10) return null
        val permissions = parts[0]
        val isDirectory = permissions[0] == 'd'
        val isLink = permissions[0] == 'l'
        val owner = parts[2]
        val group = parts[3]
        val size = parts[4].toLongOrNull() ?: 0L
        val rawName = parts.getOrNull(8) ?: parts.getOrNull(7) ?: return null
        val linkParts = if (isLink) rawName.split(" -> ", limit = 2) else emptyList()
        val name = (linkParts.firstOrNull() ?: rawName).trimEnd('/')
        if (name.isEmpty() || name == "." || name == ".." || !ShizukuPaths.isAllowed(ShizukuPaths.child(parentPath, name) ?: "")) {
            return null
        }
        val extension = name.substringAfterLast('.', "")
        val mimeType = if (isDirectory) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
        return FileItem(
            name = name,
            path = ShizukuPaths.child(parentPath, name) ?: return null,
            size = size,
            isDirectory = isDirectory,
            isHidden = name.startsWith('.'),
            isSymlink = isLink,
            mimeType = mimeType,
            extension = extension,
            permissions = parsePermissions(permissions),
            ownerName = owner,
            groupName = group,
            symlinkTarget = linkParts.getOrNull(1),
        )
    }

    private fun parsePermissions(value: String): Set<PosixFilePermission> = buildSet {
        if (value.length < 10) return@buildSet
        if (value[1] == 'r') add(PosixFilePermission.OWNER_READ)
        if (value[2] == 'w') add(PosixFilePermission.OWNER_WRITE)
        if (value[3] == 'x' || value[3] == 's') add(PosixFilePermission.OWNER_EXECUTE)
        if (value[4] == 'r') add(PosixFilePermission.GROUP_READ)
        if (value[5] == 'w') add(PosixFilePermission.GROUP_WRITE)
        if (value[6] == 'x' || value[6] == 's') add(PosixFilePermission.GROUP_EXECUTE)
        if (value[7] == 'r') add(PosixFilePermission.OTHERS_READ)
        if (value[8] == 'w') add(PosixFilePermission.OTHERS_WRITE)
        if (value[9] == 'x' || value[9] == 't') add(PosixFilePermission.OTHERS_EXECUTE)
    }

    private fun <T> unsupported(): Result<T> =
        Result.failure(IllegalArgumentException("Shizuku only supports Android/data paths"))
}
