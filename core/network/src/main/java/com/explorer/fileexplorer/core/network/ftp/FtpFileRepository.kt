package com.explorer.fileexplorer.core.network.ftp

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
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject

class FtpFileRepository @Inject constructor() : NetworkFileRepository {

    private var client: FTPClient? = null
    private var currentConnection: NetworkConnection? = null

    override val isConnected: Boolean get() = client?.isConnected == true

    override suspend fun connect(connection: NetworkConnection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val ftp = if (connection.useTls) FTPSClient(true) else FTPClient()
            ftp.connectTimeout = 15000
            ftp.defaultTimeout = 15000
            ftp.connect(connection.host, connection.port)
            ftp.login(connection.username.ifEmpty { "anonymous" }, connection.password.ifEmpty { "anonymous@" })
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.bufferSize = 65536

            if (connection.remotePath.isNotEmpty() && connection.remotePath != "/") {
                ftp.changeWorkingDirectory(connection.remotePath)
            }

            client = ftp
            currentConnection = connection
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.let { if (it.isConnected) { it.logout(); it.disconnect() } }
        } catch (_: Exception) {}
        client = null; currentConnection = null
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val ftp = client ?: run { emit(emptyList()); return@flow }
        val items = mutableListOf<FileItem>()
        try {
            val entries = ftp.listFiles(path)
            for (entry in entries) {
                if (entry.name == "." || entry.name == "..") continue
                items.add(ftpFileToFileItem(entry, path))
            }
        } catch (_: Exception) {}
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext null
        try {
            val files = ftp.listFiles(path)
            files.firstOrNull()?.let { ftpFileToFileItem(it, path.substringBeforeLast('/')) }
        } catch (_: Exception) { null }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext false
            ftp.listFiles(path).isNotEmpty()
        } catch (_: Exception) { false }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        // FTP has no server-side copy. Would need download+upload.
        Result.failure(UnsupportedOperationException("FTP does not support server-side copy"))
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val dest = "$destination/$name"
                onProgress(0, 0, name)
                if (ftp.rename(src, dest)) count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                val files = ftp.listFiles(path)
                if (files.isNotEmpty() && files.first().isDirectory) {
                    deleteFtpRecursive(ftp, path)
                } else {
                    ftp.deleteFile(path)
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun deleteFtpRecursive(ftp: FTPClient, path: String) {
        val entries = ftp.listFiles(path)
        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val fullPath = "$path/${entry.name}"
            if (entry.isDirectory) deleteFtpRecursive(ftp, fullPath) else ftp.deleteFile(fullPath)
        }
        ftp.removeDirectory(path)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(Exception("Not connected"))
            if (ftp.makeDirectory(path)) {
                getFileInfo(path)?.let { Result.success(it) }
                    ?: Result.failure(Exception("Created but stat failed"))
            } else Result.failure(Exception("mkdir failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(Exception("Not connected"))
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            if (ftp.rename(path, target)) {
                getFileInfo(target)?.let { Result.success(it) }
                    ?: Result.failure(Exception("Renamed but stat failed"))
            } else Result.failure(Exception("rename failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(Exception("Not connected"))
            FileOutputStream(localPath).use { output ->
                ftp.retrieveFile(remotePath, output)
            }
            val localSize = File(localPath).length()
            onProgress(localSize, localSize)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(Exception("Not connected"))
            val localFile = File(localPath)
            FileInputStream(localFile).use { input ->
                ftp.storeFile(remotePath, input)
            }
            onProgress(localFile.length(), localFile.length())
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun ftpFileToFileItem(file: FTPFile, parentPath: String): FileItem {
        val name = file.name
        val isDir = file.isDirectory
        val isLink = file.isSymbolicLink
        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"
        return FileItem(
            name = name, path = fullPath,
            size = file.size,
            lastModified = file.timestamp?.timeInMillis ?: 0L,
            isDirectory = isDir, isHidden = name.startsWith("."),
            isSymlink = isLink, mimeType = mime, extension = ext,
            ownerName = file.user, groupName = file.group,
        )
    }
}
