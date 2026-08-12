package com.explorer.fileexplorer.core.data

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictNamePolicy
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.repositoryException
import com.explorer.fileexplorer.core.model.unsupportedRepositoryOperation
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

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.local("shizuku")

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        if (!ShizukuPaths.isAllowed(path)) {
            throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.LIST)
        }
        val result = shizukuManager.execute("ls -lAp -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) {
            throw commandFailure(RepositoryOperation.LIST, result.output)
        }
        emit(
            result.output.lineSequence()
                .dropWhile { it.startsWith("total ") }
                .mapNotNull { parseLsLine(it, path) }
                .toList(),
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) {
            throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.INFO)
        }
        val result = shizukuManager.execute("ls -ldA -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess && result.output.isNotBlank()) {
            throw commandFailure(RepositoryOperation.INFO, result.output)
        }
        result.output.lineSequence().mapNotNull { line ->
            parseLsLine(line, path.substringBeforeLast('/', ""))
        }.firstOrNull()
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) {
            throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.EXISTS)
        }
        shizukuManager.execute("test -e ${ShizukuPaths.shellQuote(path)}").isSuccess
    }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(destination) || sources.any { !ShizukuPaths.isAllowed(it) }) {
            return@withContext unsupported(RepositoryOperation.COPY)
        }
        var count = 0
        for (source in sources) {
            onProgress(0, 0, source.substringAfterLast('/'))
            val name = source.substringAfterLast('/')
            val targetName = resolveShizukuTarget(destination, name, conflictResolution, conflictSuffix)
            if (targetName == null) {
                count++
                continue
            }
            val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-rf" else "-r"
            val targetPath = requireNotNull(ShizukuPaths.child(destination, targetName))
            val result = shizukuManager.execute(
                    "cp $flags -- ${ShizukuPaths.shellQuote(source)} ${ShizukuPaths.shellQuote(targetPath)}",
                )
            if (!result.isSuccess) return@withContext Result.failure(commandFailure(RepositoryOperation.COPY, result.output))
            count++
        }
        Result.success(count)
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(destination) || sources.any { !ShizukuPaths.isAllowed(it) }) {
            return@withContext unsupported(RepositoryOperation.MOVE)
        }
        var count = 0
        for (source in sources) {
            onProgress(0, 0, source.substringAfterLast('/'))
            val name = source.substringAfterLast('/')
            val targetName = resolveShizukuTarget(destination, name, conflictResolution, conflictSuffix)
            if (targetName == null) {
                count++
                continue
            }
            val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-f" else "-n"
            val targetPath = requireNotNull(ShizukuPaths.child(destination, targetName))
            val result = shizukuManager.execute(
                    "mv $flags -- ${ShizukuPaths.shellQuote(source)} ${ShizukuPaths.shellQuote(targetPath)}",
                )
            if (!result.isSuccess) return@withContext Result.failure(commandFailure(RepositoryOperation.MOVE, result.output))
            count++
        }
        Result.success(count)
    }

    private suspend fun resolveShizukuTarget(
        destination: String,
        sourceName: String,
        resolution: ConflictResolution,
        conflictSuffix: String?,
    ): String? {
        val requested = ShizukuPaths.child(destination, sourceName)
            ?: return null
        if (!exists(requested)) return sourceName
        return when (resolution) {
            ConflictResolution.SKIP -> null
            ConflictResolution.OVERWRITE -> sourceName
            ConflictResolution.RENAME,
            ConflictResolution.ASK,
            -> {
                val deterministic = conflictSuffix?.let { ConflictNamePolicy.fileName(sourceName, it) }
                var candidate = deterministic ?: numberedName(sourceName, 1)
                var index = 1
                while (ShizukuPaths.child(destination, candidate)?.let { exists(it) } == true) {
                    index++
                    candidate = numberedName(deterministic ?: sourceName, index)
                }
                candidate
            }
        }
    }

    private fun numberedName(name: String, number: Int): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return "$base ($number)$extension"
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (paths.any { !ShizukuPaths.isAllowed(it) || ShizukuPaths.isProtectedRoot(it) }) {
            return@withContext unsupported(RepositoryOperation.DELETE)
        }
        var count = 0
        for (path in paths) {
            onProgress(path.substringAfterLast('/'))
            val result = shizukuManager.execute("rm -rf -- ${ShizukuPaths.shellQuote(path)}")
            if (!result.isSuccess) return@withContext Result.failure(commandFailure(RepositoryOperation.DELETE, result.output))
            count++
        }
        Result.success(count)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) return@withContext unsupported(RepositoryOperation.CREATE_DIRECTORY)
        val result = shizukuManager.execute("mkdir -p -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) Result.failure(commandFailure(RepositoryOperation.CREATE_DIRECTORY, result.output))
        else getFileInfo(path)?.let(Result.Companion::success)
            ?: Result.failure(commandFailure(RepositoryOperation.CREATE_DIRECTORY, "created but metadata could not be read"))
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) return@withContext unsupported(RepositoryOperation.CREATE_FILE)
        val result = shizukuManager.execute("touch -- ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) Result.failure(commandFailure(RepositoryOperation.CREATE_FILE, result.output))
        else getFileInfo(path)?.let(Result.Companion::success)
            ?: Result.failure(commandFailure(RepositoryOperation.CREATE_FILE, "created but metadata could not be read"))
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val parent = path.substringBeforeLast('/', "")
        val target = ShizukuPaths.child(parent, newName)
            ?: return@withContext Result.failure(
                repositoryException(
                    capabilities.provider,
                    RepositoryOperation.RENAME,
                    RepositoryErrorKind.INVALID,
                    "invalid Android/data filename",
                    retryable = false,
                ),
            )
        val result = shizukuManager.execute(
            "mv -- ${ShizukuPaths.shellQuote(path)} ${ShizukuPaths.shellQuote(target)}",
        )
        if (!result.isSuccess) Result.failure(commandFailure(RepositoryOperation.RENAME, result.output))
        else getFileInfo(target)?.let(Result.Companion::success)
            ?: Result.failure(commandFailure(RepositoryOperation.RENAME, "renamed but metadata could not be read"))
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        if (paths.any { !ShizukuPaths.isAllowed(it) }) {
            throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.SIZE)
        }
        paths.sumOf { path ->
            val result = shizukuManager.execute("du -sk -- ${ShizukuPaths.shellQuote(path)}")
            if (!result.isSuccess) throw commandFailure(RepositoryOperation.SIZE, result.output)
            result.output.lineSequence().firstOrNull()?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
                ?: throw repositoryException(
                    capabilities.provider,
                    RepositoryOperation.SIZE,
                    RepositoryErrorKind.CORRUPT,
                    "size command returned invalid data",
                    retryable = false,
                )
        }
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        if (!ShizukuPaths.isAllowed(rootPath)) {
            throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.SEARCH)
        }
        val pattern = if (regex) ".*$query.*" else "*$query*"
        val hiddenFilter = if (!includeHidden) "! -name ${ShizukuPaths.shellQuote(".*")}" else ""
        val command = "find ${ShizukuPaths.shellQuote(rootPath)} -maxdepth 5 " +
            "${if (regex) "-regex" else "-iname"} ${ShizukuPaths.shellQuote(pattern)} $hiddenFilter -print"
        val result = shizukuManager.execute(command)
        if (!result.isSuccess) throw commandFailure(RepositoryOperation.SEARCH, result.output)
        result.output.lineSequence().filter { it.isNotBlank() }.forEach { path ->
            getFileInfo(path.trim())?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        if (!ShizukuPaths.isAllowed(path)) {
            throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.CHECKSUM)
        }
        val command = when (algorithm.uppercase()) {
            "MD5" -> "md5sum"
            "SHA-1", "SHA1" -> "sha1sum"
            "SHA-256", "SHA256" -> "sha256sum"
            "SHA-512", "SHA512" -> "sha512sum"
            else -> "sha256sum"
        }
        val result = shizukuManager.execute("$command ${ShizukuPaths.shellQuote(path)}")
        if (!result.isSuccess) throw commandFailure(RepositoryOperation.CHECKSUM, result.output)
        result.output.lineSequence().firstOrNull()?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }
            ?: throw repositoryException(
                capabilities.provider,
                RepositoryOperation.CHECKSUM,
                RepositoryErrorKind.CORRUPT,
                "checksum command returned invalid data",
                retryable = false,
            )
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

    private fun <T> unsupported(operation: RepositoryOperation): Result<T> =
        Result.failure(unsupportedRepositoryOperation(capabilities.provider, operation))

    private fun commandFailure(operation: RepositoryOperation, message: String) = repositoryException(
        provider = capabilities.provider,
        operation = operation,
        kind = RepositoryErrorKind.TRANSPORT,
        message = message.ifBlank { "Shizuku command failed" },
        retryable = true,
    )
}
