package com.explorer.fileexplorer.core.network.sftp

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.network.NetworkConnection
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject

class SftpFileRepository @Inject constructor() : NetworkFileRepository {

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var currentConnection: NetworkConnection? = null

    override val isConnected: Boolean get() = ssh?.isConnected == true && sftp != null

    override suspend fun connect(connection: NetworkConnection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val client = SSHClient()
            client.addHostKeyVerifier(PromiscuousVerifier()) // TODO: known_hosts support
            client.connect(connection.host, connection.port)

            // Auth: key first, then password
            if (connection.privateKeyPath.isNotEmpty()) {
                val keyFile = File(connection.privateKeyPath)
                val keyProvider: KeyProvider = if (connection.password.isNotEmpty()) {
                    client.loadKeys(keyFile.absolutePath, connection.password)
                } else {
                    client.loadKeys(keyFile.absolutePath)
                }
                client.authPublickey(connection.username, keyProvider)
            } else if (connection.password.isNotEmpty()) {
                client.authPassword(connection.username, connection.password)
            } else {
                client.authPassword(connection.username, "")
            }

            ssh = client
            sftp = client.newSFTPClient()
            currentConnection = connection
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try { sftp?.close() } catch (_: Exception) {}
        try { ssh?.disconnect() } catch (_: Exception) {}
        sftp = null; ssh = null; currentConnection = null
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val s = sftp ?: run { emit(emptyList()); return@flow }
        val items = mutableListOf<FileItem>()
        try {
            val entries = s.ls(path)
            for (entry in entries) {
                val name = entry.name
                if (name == "." || name == "..") continue
                items.add(remoteInfoToFileItem(entry, path))
            }
        } catch (_: Exception) {}
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext null
        try {
            val attrs = s.stat(path)
            val name = path.trimEnd('/').substringAfterLast('/')
            attrsToFileItem(name, path, attrs)
        } catch (_: Exception) { null }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try { sftp?.stat(path); true } catch (_: Exception) { false }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        // SFTP has no server-side copy; download then upload
        // For same-server: just do nothing or chain download+upload
        var count = 0
        try {
            val s = sftp ?: return@withContext Result.failure(Exception("Not connected"))
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destPath = "$destination/$name"
                onProgress(0, 0, name)
                // Server-side copy via SSH exec
                val session = ssh?.startSession()
                try {
                    val cmd = session?.exec("cp -r '$src' '$destPath'")
                    cmd?.inputStream?.readBytes() // wait for completion
                    cmd?.let { count++ }
                } finally {
                    try { session?.close() } catch (_: Exception) {}
                }
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destPath = "$destination/$name"
                onProgress(0, 0, name)
                s.rename(src, destPath)
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                val attrs = s.stat(path)
                if (attrs.type == FileMode.Type.DIRECTORY) {
                    deleteRecursive(s, path)
                } else {
                    s.rm(path)
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun deleteRecursive(s: SFTPClient, path: String) {
        val entries = s.ls(path)
        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val fullPath = "$path/${entry.name}"
            if (entry.isDirectory) deleteRecursive(s, fullPath) else s.rm(fullPath)
        }
        s.rmdir(path)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            sftp?.mkdir(path)
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(Exception("Created but stat failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            sftp?.rename(path, target)
            getFileInfo(target)?.let { Result.success(it) }
                ?: Result.failure(Exception("Renamed but stat failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = sftp ?: return@withContext Result.failure(Exception("Not connected"))
            val totalSize = s.stat(remotePath).size
            s.get(remotePath, FileSystemFile(localPath))
            onProgress(totalSize, totalSize)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = sftp ?: return@withContext Result.failure(Exception("Not connected"))
            val localFile = File(localPath)
            s.put(FileSystemFile(localFile), remotePath)
            onProgress(localFile.length(), localFile.length())
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Execute a command over SSH. */
    suspend fun execCommand(command: String): String = withContext(Dispatchers.IO) {
        val session = ssh?.startSession() ?: return@withContext ""
        try {
            val cmd = session.exec(command)
            cmd.inputStream.bufferedReader().readText()
        } finally {
            session.close()
        }
    }

    private fun remoteInfoToFileItem(info: RemoteResourceInfo, parentPath: String): FileItem {
        val name = info.name
        val attrs = info.attributes
        return attrsToFileItem(name, "$parentPath/$name", attrs)
    }

    private fun attrsToFileItem(name: String, path: String, attrs: FileAttributes): FileItem {
        val isDir = attrs.type == FileMode.Type.DIRECTORY
        val isLink = attrs.type == FileMode.Type.SYMLINK
        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        val permissions = parseSftpPermissions(attrs.permissions)
        return FileItem(
            name = name, path = path,
            size = attrs.size, lastModified = attrs.mtime * 1000L,
            isDirectory = isDir, isHidden = name.startsWith("."),
            isSymlink = isLink, mimeType = mime, extension = ext,
            permissions = permissions,
            ownerName = attrs.uid.toString(),
            groupName = attrs.gid.toString(),
        )
    }

    private fun parseSftpPermissions(perms: Set<*>): Set<PosixFilePermission> {
        // Map sshj permission enum names to PosixFilePermission
        val result = mutableSetOf<PosixFilePermission>()
        for (p in perms) {
            when (p.toString()) {
                "USR_R" -> result.add(PosixFilePermission.OWNER_READ)
                "USR_W" -> result.add(PosixFilePermission.OWNER_WRITE)
                "USR_X" -> result.add(PosixFilePermission.OWNER_EXECUTE)
                "GRP_R" -> result.add(PosixFilePermission.GROUP_READ)
                "GRP_W" -> result.add(PosixFilePermission.GROUP_WRITE)
                "GRP_X" -> result.add(PosixFilePermission.GROUP_EXECUTE)
                "OTH_R" -> result.add(PosixFilePermission.OTHERS_READ)
                "OTH_W" -> result.add(PosixFilePermission.OTHERS_WRITE)
                "OTH_X" -> result.add(PosixFilePermission.OTHERS_EXECUTE)
            }
        }
        return result
    }
}
