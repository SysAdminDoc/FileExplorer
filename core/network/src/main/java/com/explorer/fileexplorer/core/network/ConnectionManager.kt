package com.explorer.fileexplorer.core.network

import com.explorer.fileexplorer.core.database.ConnectionDao
import com.explorer.fileexplorer.core.database.ConnectionEntity
import com.explorer.fileexplorer.core.network.ftp.FtpFileRepository
import com.explorer.fileexplorer.core.network.sftp.SftpFileRepository
import com.explorer.fileexplorer.core.network.smb.SmbFileRepository
import com.explorer.fileexplorer.core.network.webdav.WebDavFileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val connectionDao: ConnectionDao,
) {
    private val _activeConnections = MutableStateFlow<Map<Long, ActiveConnection>>(emptyMap())
    val activeConnections: StateFlow<Map<Long, ActiveConnection>> = _activeConnections.asStateFlow()

    val savedConnections: Flow<List<ConnectionEntity>> = connectionDao.getAllFlow()

    suspend fun connect(entity: ConnectionEntity): Result<NetworkFileRepository> {
        val connection = entityToConnection(entity)
        val repo = createRepository(connection.protocol)

        val result = repo.connect(connection)
        if (result.isSuccess) {
            connectionDao.updateLastConnected(entity.id)
            _activeConnections.value = _activeConnections.value + (entity.id to ActiveConnection(entity, repo))
        }
        return result.map { repo }
    }

    suspend fun disconnect(connectionId: Long) {
        val active = _activeConnections.value[connectionId]
        active?.repo?.disconnect()
        _activeConnections.value = _activeConnections.value - connectionId
    }

    suspend fun disconnectAll() {
        for ((_, active) in _activeConnections.value) {
            try { active.repo.disconnect() } catch (_: Exception) {}
        }
        _activeConnections.value = emptyMap()
    }

    fun getActiveRepo(connectionId: Long): NetworkFileRepository? {
        return _activeConnections.value[connectionId]?.repo
    }

    fun isConnected(connectionId: Long): Boolean {
        return _activeConnections.value[connectionId]?.repo?.isConnected == true
    }

    suspend fun saveConnection(entity: ConnectionEntity): Long {
        return connectionDao.insert(entity)
    }

    suspend fun deleteConnection(entity: ConnectionEntity) {
        disconnect(entity.id)
        connectionDao.delete(entity)
    }

    suspend fun testConnection(entity: ConnectionEntity): Result<Unit> {
        val connection = entityToConnection(entity)
        val repo = createRepository(connection.protocol)
        val result = repo.connect(connection)
        if (result.isSuccess) repo.disconnect()
        return result
    }

    private fun createRepository(protocol: Protocol): NetworkFileRepository {
        return when (protocol) {
            Protocol.SMB -> SmbFileRepository()
            Protocol.SFTP -> SftpFileRepository()
            Protocol.FTP, Protocol.FTPS -> FtpFileRepository()
            Protocol.WEBDAV -> WebDavFileRepository()
        }
    }

    private fun entityToConnection(entity: ConnectionEntity): NetworkConnection {
        val protocol = Protocol.entries.firstOrNull { it.uriScheme == entity.protocol } ?: Protocol.SMB
        return NetworkConnection(
            id = entity.id, name = entity.name, protocol = protocol,
            host = entity.host, port = entity.port,
            username = entity.username, password = entity.password,
            shareName = entity.shareName, remotePath = entity.remotePath,
            privateKeyPath = entity.privateKeyPath, useTls = entity.useTls,
        )
    }
}

data class ActiveConnection(
    val entity: ConnectionEntity,
    val repo: NetworkFileRepository,
)
