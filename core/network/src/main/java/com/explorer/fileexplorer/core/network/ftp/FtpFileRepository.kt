package com.explorer.fileexplorer.core.network.ftp

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.model.isMissingRepositoryResource
import com.explorer.fileexplorer.core.model.notConnectedRepositoryException
import com.explorer.fileexplorer.core.model.repositoryException
import com.explorer.fileexplorer.core.model.unsupportedRepositoryOperation
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

    override val capabilities: RepositoryCapabilities
        get() = RepositoryCapabilities.network(if (currentConnection?.useTls == true) "ftps" else "ftp", serverSideCopy = false)

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
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CONNECT))
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.let { if (it.isConnected) { it.logout(); it.disconnect() } }
        } catch (_: Exception) {}
        client = null; currentConnection = null
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        val items = mutableListOf<FileItem>()
        try {
            val entries = ftp.listFiles(path)
            for (entry in entries) {
                if (entry.name == "." || entry.name == "..") continue
                items.add(ftpFileToFileItem(entry, path))
            }
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        try {
            val files = ftp.listFiles(path)
            files.firstOrNull()?.let { ftpFileToFileItem(it, path.substringBeforeLast('/')) }
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) null
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
            ftp.listFiles(path).isNotEmpty()
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) false
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        // FTP has no server-side copy. Would need download+upload.
        Result.failure(unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.COPY))
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.MOVE),
        )
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val dest = "$destination/$name"
                onProgress(0, 0, name)
                if (ftp.rename(src, dest)) count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DELETE),
        )
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
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
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
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY),
            )
            if (ftp.makeDirectory(path)) {
                getFileInfo(path)?.let { Result.success(it) }
                    ?: Result.failure(repositoryException(
                        capabilities.provider,
                        RepositoryOperation.CREATE_DIRECTORY,
                        RepositoryErrorKind.TRANSPORT,
                        "created but metadata could not be read",
                        retryable = true,
                    ))
            } else Result.failure(repositoryException(
                capabilities.provider,
                RepositoryOperation.CREATE_DIRECTORY,
                RepositoryErrorKind.TRANSPORT,
                "mkdir failed",
                retryable = true,
            ))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY))
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.RENAME),
            )
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            if (ftp.rename(path, target)) {
                getFileInfo(target)?.let { Result.success(it) }
                    ?: Result.failure(repositoryException(
                        capabilities.provider,
                        RepositoryOperation.RENAME,
                        RepositoryErrorKind.TRANSPORT,
                        "renamed but metadata could not be read",
                        retryable = true,
                    ))
            } else Result.failure(repositoryException(
                capabilities.provider,
                RepositoryOperation.RENAME,
                RepositoryErrorKind.TRANSPORT,
                "rename failed",
                retryable = true,
            ))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.RENAME))
        }
    }

    override suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD),
            )
            FileOutputStream(localPath).use { output ->
                ftp.retrieveFile(remotePath, output)
            }
            val localSize = File(localPath).length()
            onProgress(localSize, localSize)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD))
        }
    }

    override suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD),
            )
            val localFile = File(localPath)
            FileInputStream(localFile).use { input ->
                ftp.storeFile(remotePath, input)
            }
            onProgress(localFile.length(), localFile.length())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD))
        }
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
