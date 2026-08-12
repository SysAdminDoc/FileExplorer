package com.explorer.fileexplorer.core.data

import android.content.Context
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictNamePolicy
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.model.repositoryException
import com.explorer.fileexplorer.core.storage.RootHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootHelper: RootHelper,
) : FileRepository {

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.local("root")

    private fun esc(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun resolveRootTarget(
        destination: String,
        sourceName: String,
        resolution: ConflictResolution,
        conflictSuffix: String?,
    ): String? {
        val requested = destination.trimEnd('/') + "/" + sourceName
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
                while (exists(destination.trimEnd('/') + "/" + candidate)) {
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

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val p = esc(path)
        val result = rootHelper.exec(
            "ls -lAp --color=never $p 2>/dev/null || ls -la $p 2>/dev/null"
        )
        if (!result.isSuccess) {
            throw commandFailure(RepositoryOperation.LIST, result.err.joinToString())
        }
        val items = result.out
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLsLine(line, path) }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("ls -ldA ${esc(path)} 2>/dev/null")
        if (!result.isSuccess) {
            if (result.err.joinToString().isMissingPathMessage()) return@withContext null
            throw commandFailure(RepositoryOperation.INFO, result.err.joinToString())
        }
        if (result.out.isEmpty()) return@withContext null
        parseLsLine(result.out.first(), path.substringBeforeLast('/'))
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("test -e ${esc(path)}")
        if (!result.isSuccess && !result.err.joinToString().isMissingPathMessage()) {
            throw commandFailure(RepositoryOperation.EXISTS, result.err.joinToString())
        }
        result.isSuccess
    }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (src in sources) {
                val name = src.substringAfterLast('/')
                onProgress(0, 0, name)
                val targetName = resolveRootTarget(destination, name, conflictResolution, conflictSuffix)
                if (targetName == null) {
                    count++
                    continue
                }
                val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-rf" else "-r"
                val result = rootHelper.exec("cp $flags ${esc(src)} ${esc(destination.trimEnd('/') + "/" + targetName)}")
                if (!result.isSuccess) {
                    return@withContext Result.failure(commandFailure(RepositoryOperation.COPY, result.err.joinToString()))
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.COPY))
        }
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (src in sources) {
                val name = src.substringAfterLast('/')
                onProgress(0, 0, name)
                val targetName = resolveRootTarget(destination, name, conflictResolution, conflictSuffix)
                if (targetName == null) {
                    count++
                    continue
                }
                val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-f" else ""
                val result = rootHelper.exec("mv $flags ${esc(src)} ${esc(destination.trimEnd('/') + "/" + targetName)}")
                if (!result.isSuccess) {
                    return@withContext Result.failure(commandFailure(RepositoryOperation.MOVE, result.err.joinToString()))
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                val result = rootHelper.exec("rm -rf ${esc(path)}")
                if (!result.isSuccess) {
                    return@withContext Result.failure(commandFailure(RepositoryOperation.DELETE, result.err.joinToString()))
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("mkdir -p ${esc(path)}")
        if (result.isSuccess) {
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(commandFailure(RepositoryOperation.CREATE_DIRECTORY, "created but metadata could not be read"))
        } else {
            Result.failure(commandFailure(RepositoryOperation.CREATE_DIRECTORY, result.err.joinToString()))
        }
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("touch ${esc(path)}")
        if (result.isSuccess) {
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(commandFailure(RepositoryOperation.CREATE_FILE, "created but metadata could not be read"))
        } else {
            Result.failure(commandFailure(RepositoryOperation.CREATE_FILE, result.err.joinToString()))
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val parent = path.substringBeforeLast('/')
        val target = "$parent/$newName"
        val result = rootHelper.exec("mv ${esc(path)} ${esc(target)}")
        if (result.isSuccess) {
            getFileInfo(target)?.let { Result.success(it) }
                ?: Result.failure(commandFailure(RepositoryOperation.RENAME, "renamed but metadata could not be read"))
        } else {
            Result.failure(commandFailure(RepositoryOperation.RENAME, result.err.joinToString()))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        var total = 0L
        for (path in paths) {
            val result = rootHelper.exec("du -sb ${esc(path)} 2>/dev/null | tail -1")
            if (!result.isSuccess) throw commandFailure(RepositoryOperation.SIZE, result.err.joinToString())
            if (result.out.isEmpty()) throw commandFailure(RepositoryOperation.SIZE, "size command returned no data")
            val size = result.out.first().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull()
                ?: throw repositoryException(
                    capabilities.provider,
                    RepositoryOperation.SIZE,
                    RepositoryErrorKind.CORRUPT,
                    "size command returned invalid data",
                    retryable = false,
                )
            total += size
        }
        total
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        val namePattern = if (regex) "-regex ${esc(".*$query.*")}" else "-iname ${esc("*$query*")}"
        val hiddenFilter = if (!includeHidden) "! -name '.*'" else ""
        val result = rootHelper.exec(
            "find ${esc(rootPath)} -maxdepth 5 $namePattern $hiddenFilter 2>/dev/null | head -500"
        )
        if (!result.isSuccess) throw commandFailure(RepositoryOperation.SEARCH, result.err.joinToString())
        for (line in result.out) {
            if (line.isBlank()) continue
            getFileInfo(line.trim())?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val cmd = when (algorithm.uppercase()) {
            "MD5" -> "md5sum"
            "SHA-1", "SHA1" -> "sha1sum"
            "SHA-256", "SHA256" -> "sha256sum"
            "SHA-512", "SHA512" -> "sha512sum"
            else -> "sha256sum"
        }
        val result = rootHelper.exec("$cmd ${esc(path)} 2>/dev/null")
        if (!result.isSuccess) throw commandFailure(RepositoryOperation.CHECKSUM, result.err.joinToString())
        if (result.out.isEmpty()) throw commandFailure(RepositoryOperation.CHECKSUM, "checksum command returned no data")
        result.out.first().split("\\s+".toRegex()).firstOrNull()
            ?: throw commandFailure(RepositoryOperation.CHECKSUM, "checksum command returned invalid data")
    }

    suspend fun getSelinuxContext(path: String): String? = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("ls -Z ${esc(path)} 2>/dev/null")
        if (result.isSuccess && result.out.isNotEmpty()) {
            result.out.first().split("\\s+".toRegex()).firstOrNull()
        } else null
    }

    suspend fun getMountPoints(): List<MountPoint> = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("mount 2>/dev/null")
        if (!result.isSuccess) return@withContext emptyList()
        result.out.mapNotNull { line ->
            val parts = line.split(" on ", " type ")
            if (parts.size >= 3) {
                val device = parts[0].trim()
                val mountPoint = parts[1].trim()
                val rest = parts[2].split(" ", limit = 2)
                val fsType = rest.getOrNull(0)?.trim() ?: ""
                val options = rest.getOrNull(1)?.removeSurrounding("(", ")")?.trim() ?: ""
                MountPoint(device, mountPoint, fsType, options)
            } else null
        }
    }

    suspend fun remountRw(mountPoint: String): Boolean = withContext(Dispatchers.IO) {
        rootHelper.exec("mount -o remount,rw ${esc(mountPoint)}").isSuccess
    }

    suspend fun remountRo(mountPoint: String): Boolean = withContext(Dispatchers.IO) {
        rootHelper.exec("mount -o remount,ro ${esc(mountPoint)}").isSuccess
    }

    private fun commandFailure(operation: RepositoryOperation, message: String) = repositoryException(
        provider = capabilities.provider,
        operation = operation,
        kind = RepositoryErrorKind.TRANSPORT,
        message = message.ifBlank { "root command failed" },
        retryable = true,
    )

    private fun String.isMissingPathMessage(): Boolean {
        val normalized = lowercase()
        return "no such file" in normalized || "not found" in normalized || "cannot access" in normalized
    }

    suspend fun chmod(path: String, mode: String, recursive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!mode.matches(Regex("[0-7]{3,4}"))) return@withContext false
        val flags = if (recursive) "-R" else ""
        rootHelper.exec("chmod $flags $mode ${esc(path)}").isSuccess
    }

    suspend fun chown(path: String, owner: String, group: String, recursive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!owner.matches(Regex("[a-zA-Z0-9._-]+")) || !group.matches(Regex("[a-zA-Z0-9._-]+"))) return@withContext false
        val flags = if (recursive) "-R" else ""
        rootHelper.exec("chown $flags $owner:$group ${esc(path)}").isSuccess
    }

    // -- ls -l parser --

    private fun parseLsLine(line: String, parentPath: String): FileItem? {
        // Format: drwxr-xr-x 2 root root 4096 2024-01-15 10:30 filename
        // Or:     -rw-r--r-- 1 root root  123 Jan 15 10:30 filename
        val parts = line.trim().split("\\s+".toRegex(), limit = 9)
        if (parts.size < 8) return null

        val permsStr = parts[0]
        if (permsStr.length < 10) return null

        val isDir = permsStr[0] == 'd'
        val isLink = permsStr[0] == 'l'
        val owner = parts[2]
        val group = parts[3]
        val size = parts[4].toLongOrNull() ?: 0L

        // Name is the last field — may contain spaces
        val name: String
        val symlinkTarget: String?

        // Handle the varying date formats
        val rawName = if (parts.size >= 9) parts[8]
        else if (parts.size >= 8) parts[7]
        else return null

        if (isLink && rawName.contains(" -> ")) {
            val linkParts = rawName.split(" -> ", limit = 2)
            name = linkParts[0].trimEnd('/')
            symlinkTarget = linkParts[1]
        } else {
            name = rawName.trimEnd('/')
            symlinkTarget = null
        }

        if (name.isEmpty() || name == "." || name == "..") return null

        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"

        val permissions = parsePermissions(permsStr)

        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"

        return FileItem(
            name = name,
            path = fullPath,
            size = size,
            lastModified = 0L, // Would need stat -c %Y for precise timestamp
            isDirectory = isDir,
            isHidden = name.startsWith("."),
            isSymlink = isLink,
            isReadable = true, // We have root, assume readable
            isWritable = true,
            mimeType = mime,
            extension = ext,
            permissions = permissions,
            ownerName = owner,
            groupName = group,
            symlinkTarget = symlinkTarget,
        )
    }

    private fun parsePermissions(perms: String): Set<PosixFilePermission> {
        if (perms.length < 10) return emptySet()
        val set = mutableSetOf<PosixFilePermission>()
        if (perms[1] == 'r') set.add(PosixFilePermission.OWNER_READ)
        if (perms[2] == 'w') set.add(PosixFilePermission.OWNER_WRITE)
        if (perms[3] == 'x' || perms[3] == 's') set.add(PosixFilePermission.OWNER_EXECUTE)
        if (perms[4] == 'r') set.add(PosixFilePermission.GROUP_READ)
        if (perms[5] == 'w') set.add(PosixFilePermission.GROUP_WRITE)
        if (perms[6] == 'x' || perms[6] == 's') set.add(PosixFilePermission.GROUP_EXECUTE)
        if (perms[7] == 'r') set.add(PosixFilePermission.OTHERS_READ)
        if (perms[8] == 'w') set.add(PosixFilePermission.OTHERS_WRITE)
        if (perms[9] == 'x' || perms[9] == 't') set.add(PosixFilePermission.OTHERS_EXECUTE)
        return set
    }
}

data class MountPoint(
    val device: String,
    val mountPoint: String,
    val fsType: String,
    val options: String,
) {
    val isReadOnly: Boolean get() = options.contains("ro")
}
