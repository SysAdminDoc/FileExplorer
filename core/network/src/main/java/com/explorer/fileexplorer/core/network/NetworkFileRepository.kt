package com.explorer.fileexplorer.core.network

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Connection configuration for all network protocols.
 */
data class NetworkConnection(
    val id: Long = 0,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val shareName: String = "",       // SMB share name
    val remotePath: String = "/",
    val privateKeyPath: String = "",  // SFTP key auth
    val useTls: Boolean = false,      // FTPS / WebDAV HTTPS
)

enum class Protocol(val displayName: String, val defaultPort: Int, val uriScheme: String) {
    SMB("SMB/CIFS", 445, "smb"),
    SFTP("SFTP", 22, "sftp"),
    FTP("FTP", 21, "ftp"),
    FTPS("FTPS", 990, "ftps"),
    WEBDAV("WebDAV", 443, "webdav"),
}

/**
 * Abstract base for all network file repositories.
 * Extends FileRepository contract with connect/disconnect lifecycle.
 */
interface NetworkFileRepository {
    val isConnected: Boolean

    suspend fun connect(connection: NetworkConnection): Result<Unit>
    suspend fun disconnect()

    fun listFiles(path: String): Flow<List<FileItem>>
    suspend fun getFileInfo(path: String): FileItem?
    suspend fun exists(path: String): Boolean

    suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.OVERWRITE,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Int>

    suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.OVERWRITE,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Int>

    suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit = {}): Result<Int>
    suspend fun createDirectory(path: String): Result<FileItem>
    suspend fun rename(path: String, newName: String): Result<FileItem>

    /** Download a remote file to local path. */
    suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Unit>

    /** Upload a local file to remote path. */
    suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Unit>
}
