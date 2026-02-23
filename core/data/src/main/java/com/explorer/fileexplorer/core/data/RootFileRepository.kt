package com.explorer.fileexplorer.core.data

import android.content.Context
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
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

/**
 * FileRepository implementation using root shell (libsu) for
 * accessing restricted paths: /data, /system, /vendor, etc.
 */
@Singleton
class RootFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootHelper: RootHelper,
) : FileRepository {

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val result = rootHelper.exec(
            "ls -lAp --color=never '$path' 2>/dev/null || ls -la '$path' 2>/dev/null"
        )
        if (!result.isSuccess) {
            emit(emptyList())
            return@flow
        }
        val items = result.out
            .drop(1) // Skip "total N" line
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLsLine(line, path) }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("ls -ldA '$path' 2>/dev/null")
        if (!result.isSuccess || result.out.isEmpty()) return@withContext null
        parseLsLine(result.out.first(), path.substringBeforeLast('/'))
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        rootHelper.exec("test -e '$path'").isSuccess
    }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (src in sources) {
                val name = src.substringAfterLast('/')
                onProgress(0, 0, name)

                val flags = when (conflictResolution) {
                    ConflictResolution.OVERWRITE -> "-rf"
                    ConflictResolution.SKIP -> "-n"
                    else -> "-rf"
                }
                val result = rootHelper.exec("cp $flags '$src' '$destination/'")
                if (result.isSuccess) count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
                val name = src.substringAfterLast('/')
                onProgress(0, 0, name)
                val flags = if (conflictResolution == ConflictResolution.OVERWRITE) "-f" else ""
                val result = rootHelper.exec("mv $flags '$src' '$destination/'")
                if (result.isSuccess) count++
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
                onProgress(path.substringAfterLast('/'))
                val result = rootHelper.exec("rm -rf '$path'")
                if (result.isSuccess) count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("mkdir -p '$path'")
        if (result.isSuccess) {
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(Exception("Created but cannot stat"))
        } else {
            Result.failure(Exception(result.err.joinToString()))
        }
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("touch '$path'")
        if (result.isSuccess) {
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(Exception("Created but cannot stat"))
        } else {
            Result.failure(Exception(result.err.joinToString()))
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val parent = path.substringBeforeLast('/')
        val target = "$parent/$newName"
        val result = rootHelper.exec("mv '$path' '$target'")
        if (result.isSuccess) {
            getFileInfo(target)?.let { Result.success(it) }
                ?: Result.failure(Exception("Renamed but cannot stat"))
        } else {
            Result.failure(Exception(result.err.joinToString()))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        var total = 0L
        for (path in paths) {
            val result = rootHelper.exec("du -sb '$path' 2>/dev/null | tail -1")
            if (result.isSuccess && result.out.isNotEmpty()) {
                result.out.first().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull()?.let {
                    total += it
                }
            }
        }
        total
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        val namePattern = if (regex) "-regex '.*$query.*'" else "-iname '*$query*'"
        val hiddenFilter = if (!includeHidden) "! -name '.*'" else ""
        val result = rootHelper.exec(
            "find '$rootPath' -maxdepth 5 $namePattern $hiddenFilter 2>/dev/null | head -500"
        )
        if (result.isSuccess) {
            for (line in result.out) {
                if (line.isBlank()) continue
                getFileInfo(line.trim())?.let { emit(it) }
            }
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
        val result = rootHelper.exec("$cmd '$path' 2>/dev/null")
        if (result.isSuccess && result.out.isNotEmpty()) {
            result.out.first().split("\\s+".toRegex()).firstOrNull() ?: ""
        } else ""
    }

    // -- Additional root-specific operations --

    /** Get SELinux context for a file. */
    suspend fun getSelinuxContext(path: String): String? = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("ls -Z '$path' 2>/dev/null")
        if (result.isSuccess && result.out.isNotEmpty()) {
            // Format: context filename
            result.out.first().split("\\s+".toRegex()).firstOrNull()
        } else null
    }

    /** Get mount points. */
    suspend fun getMountPoints(): List<MountPoint> = withContext(Dispatchers.IO) {
        val result = rootHelper.exec("mount 2>/dev/null")
        if (!result.isSuccess) return@withContext emptyList()
        result.out.mapNotNull { line ->
            // Format: device on /mount/point type filesystem (options)
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

    /** Remount a partition as read-write. */
    suspend fun remountRw(mountPoint: String): Boolean = withContext(Dispatchers.IO) {
        rootHelper.exec("mount -o remount,rw '$mountPoint'").isSuccess
    }

    /** Remount a partition as read-only. */
    suspend fun remountRo(mountPoint: String): Boolean = withContext(Dispatchers.IO) {
        rootHelper.exec("mount -o remount,ro '$mountPoint'").isSuccess
    }

    /** Change file permissions. */
    suspend fun chmod(path: String, mode: String, recursive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val flags = if (recursive) "-R" else ""
        rootHelper.exec("chmod $flags $mode '$path'").isSuccess
    }

    /** Change file ownership. */
    suspend fun chown(path: String, owner: String, group: String, recursive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val flags = if (recursive) "-R" else ""
        rootHelper.exec("chown $flags $owner:$group '$path'").isSuccess
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
