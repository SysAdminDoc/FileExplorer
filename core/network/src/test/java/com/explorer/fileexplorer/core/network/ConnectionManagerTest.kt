package com.explorer.fileexplorer.core.network

import com.explorer.fileexplorer.core.database.ConnectionDao
import com.explorer.fileexplorer.core.database.ConnectionEntity
import com.explorer.fileexplorer.core.network.sftp.SftpKnownHostsStore
import com.explorer.fileexplorer.core.storage.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionManagerTest {
    @Test
    fun saveConnectionEncryptsPasswordBeforePersisting() = runBlocking {
        val dao = FakeConnectionDao()
        val manager = connectionManager(dao)

        manager.saveConnection(connection(password = "secret"))

        assertEquals("enc:secret", dao.rows.value.single().password)
    }

    @Test
    fun savedConnectionsMigratesPlaintextAndEmitsDecryptedPasswords() = runBlocking {
        val dao = FakeConnectionDao(
            initialRows = listOf(
                connection(id = 1, password = "plain"),
                connection(id = 2, password = "enc:saved"),
            ),
        )
        val manager = connectionManager(dao)

        val emitted = manager.savedConnections.first()

        assertEquals(listOf("plain", "saved"), emitted.map { it.password })
        assertEquals("enc:plain", dao.rows.value.first { it.id == 1L }.password)
        assertEquals("enc:saved", dao.rows.value.first { it.id == 2L }.password)
    }

    @Test
    fun deleteConnectionDeletesByIdWithoutPlaintextEntityMatch() = runBlocking {
        val dao = FakeConnectionDao(initialRows = listOf(connection(id = 7, password = "enc:secret")))
        val manager = connectionManager(dao)

        manager.deleteConnection(connection(id = 7, password = "secret"))

        assertTrue(dao.rows.value.isEmpty())
    }

    private fun connectionManager(dao: ConnectionDao): ConnectionManager {
        val knownHostsFile = Files.createTempDirectory("fileexplorer-known-hosts").resolve("known_hosts").toFile()
        return ConnectionManager(dao, FakeCredentialCipher(), SftpKnownHostsStore(knownHostsFile, testOnly = true))
    }

    private fun connection(
        id: Long = 0,
        password: String,
    ): ConnectionEntity = ConnectionEntity(
        id = id,
        name = "Test",
        protocol = "sftp",
        host = "example.test",
        port = 22,
        username = "user",
        password = password,
    )

    private class FakeCredentialCipher : CredentialCipher {
        override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

        override fun encrypt(plainText: String): String {
            if (plainText.isEmpty() || isEncrypted(plainText)) return plainText
            return "$PREFIX$plainText"
        }

        override fun decrypt(storedText: String): String {
            return if (isEncrypted(storedText)) storedText.removePrefix(PREFIX) else storedText
        }

        private companion object {
            const val PREFIX = "enc:"
        }
    }

    private class FakeConnectionDao(initialRows: List<ConnectionEntity> = emptyList()) : ConnectionDao {
        val rows = MutableStateFlow(initialRows)
        private var nextId = (initialRows.maxOfOrNull { it.id } ?: 0L) + 1L

        override fun getAllFlow(): Flow<List<ConnectionEntity>> = rows

        override suspend fun getAll(): List<ConnectionEntity> = rows.value

        override suspend fun getById(id: Long): ConnectionEntity? = rows.value.firstOrNull { it.id == id }

        override fun getByProtocolFlow(protocol: String): Flow<List<ConnectionEntity>> = MutableStateFlow(
            rows.value.filter { it.protocol == protocol },
        )

        override suspend fun insert(connection: ConnectionEntity): Long {
            val id = if (connection.id == 0L) nextId++ else connection.id
            val stored = connection.copy(id = id)
            rows.value = rows.value.filter { it.id != id } + stored
            return id
        }

        override suspend fun update(connection: ConnectionEntity) {
            rows.value = rows.value.map { if (it.id == connection.id) connection else it }
        }

        override suspend fun delete(connection: ConnectionEntity) {
            rows.value = rows.value.filter { it != connection }
        }

        override suspend fun deleteById(id: Long) {
            rows.value = rows.value.filter { it.id != id }
        }

        override suspend fun updateLastConnected(id: Long, timestamp: Long) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(lastConnected = timestamp) else it
            }
        }
    }
}
