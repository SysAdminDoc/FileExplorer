package com.explorer.fileexplorer.core.network

import com.explorer.fileexplorer.core.database.ConnectionDao
import com.explorer.fileexplorer.core.database.ConnectionEntity
import com.explorer.fileexplorer.core.network.ftp.FtpFileRepository
import com.explorer.fileexplorer.core.network.sftp.SftpFileRepository
import com.explorer.fileexplorer.core.network.sftp.SftpHostKeyChallenge
import com.explorer.fileexplorer.core.network.sftp.SftpKnownHostsStore
import com.explorer.fileexplorer.core.network.smb.SmbFileRepository
import com.explorer.fileexplorer.core.network.webdav.WebDavFileRepository
import com.explorer.fileexplorer.core.storage.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val connectionDao: ConnectionDao,
    private val credentialCipher: CredentialCipher,
    private val sftpKnownHostsStore: SftpKnownHostsStore,
) {
    private val _activeConnections = MutableStateFlow<Map<Long, ActiveConnection>>(emptyMap())
    val activeConnections: StateFlow<Map<Long, ActiveConnection>> = _activeConnections.asStateFlow()

    val savedConnections: Flow<List<ConnectionEntity>> = connectionDao.getAllFlow()
        .onEach(::migratePlaintextPasswords)
        .map { connections -> connections.map(::decryptPassword) }

    suspend fun connect(entity: ConnectionEntity): Result<NetworkFileRepository> {
        val decryptedEntity = try {
            decryptPassword(entity)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val connection = entityToConnection(decryptedEntity)
        val repo = createRepository(connection.protocol)

        val result = repo.connect(connection)
        if (result.isSuccess) {
            connectionDao.updateLastConnected(decryptedEntity.id)
            _activeConnections.value = _activeConnections.value + (decryptedEntity.id to ActiveConnection(decryptedEntity, repo))
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
        return connectionDao.insert(encryptPassword(entity))
    }

    suspend fun deleteConnection(entity: ConnectionEntity) {
        disconnect(entity.id)
        connectionDao.deleteById(entity.id)
    }

    suspend fun testConnection(entity: ConnectionEntity): Result<Unit> {
        val decryptedEntity = try {
            decryptPassword(entity)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val connection = entityToConnection(decryptedEntity)
        val repo = createRepository(connection.protocol)
        val result = repo.connect(connection)
        if (result.isSuccess) repo.disconnect()
        return result
    }

    fun trustSftpHostKey(challenge: SftpHostKeyChallenge): Result<Unit> = runCatching {
        sftpKnownHostsStore.trust(challenge)
    }

    private fun createRepository(protocol: Protocol): NetworkFileRepository {
        return when (protocol) {
            Protocol.SMB -> SmbFileRepository()
            Protocol.SFTP -> SftpFileRepository(sftpKnownHostsStore)
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

    private suspend fun migratePlaintextPasswords(connections: List<ConnectionEntity>) {
        connections
            .filter { it.password.isNotEmpty() && !credentialCipher.isEncrypted(it.password) }
            .forEach { connectionDao.update(encryptPassword(it)) }
    }

    private fun encryptPassword(entity: ConnectionEntity): ConnectionEntity {
        return entity.copy(password = credentialCipher.encrypt(entity.password))
    }

    private fun decryptPassword(entity: ConnectionEntity): ConnectionEntity {
        return entity.copy(password = credentialCipher.decrypt(entity.password))
    }
}

data class ActiveConnection(
    val entity: ConnectionEntity,
    val repo: NetworkFileRepository,
)
