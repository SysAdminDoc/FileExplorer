package com.explorer.fileexplorer.core.network

import com.explorer.fileexplorer.core.data.DiagnosticLog
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperationCost
import com.explorer.fileexplorer.core.model.RepositoryOperationLimits
import com.explorer.fileexplorer.core.model.RepositoryException
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.network.ftp.FtpFileRepository
import com.explorer.fileexplorer.core.network.sftp.SftpFileRepository
import com.explorer.fileexplorer.core.network.sftp.SftpKnownHostsStore
import com.explorer.fileexplorer.core.network.smb.SmbFileRepository
import com.explorer.fileexplorer.core.network.webdav.WebDavFileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryCapabilitiesTest {
    @Test
    fun builtInProtocolsDeclareTheirSupportedOperations() {
        val knownHosts = Files.createTempDirectory("fileexplorer-capabilities").resolve("known_hosts").toFile()
        val repositories = listOf(
            SmbFileRepository(),
            SftpFileRepository(SftpKnownHostsStore(knownHosts, testOnly = true), DiagnosticLog()),
            FtpFileRepository(),
            WebDavFileRepository(),
        )

        assertEquals(setOf("smb", "sftp", "ftp", "webdav"), repositories.map { it.capabilities.provider }.toSet())
        repositories.forEach { repository ->
            assertTrue(repository.capabilities.supports(RepositoryOperation.LIST))
            assertTrue(repository.capabilities.supports(RepositoryOperation.DOWNLOAD))
            assertTrue(repository.capabilities.supports(RepositoryOperation.UPLOAD))
            assertFalse(repository.capabilities.supports(RepositoryOperation.CREATE_FILE))
            assertTrue(repository.capabilities.supports(RepositoryOperation.SIZE))
            assertTrue(repository.capabilities.supports(RepositoryOperation.SEARCH))
            assertTrue(repository.capabilities.supports(RepositoryOperation.CHECKSUM))
            assertEquals(RepositoryOperationCost.HIGH, repository.capabilities.semantics(RepositoryOperation.SIZE)?.cost)
            assertTrue(repository.capabilities.semantics(RepositoryOperation.SEARCH)?.cancellable == true)
        }
    }

    @Test
    fun disconnectedAdaptersUseTheSameTypedListFailureContract() = runBlocking {
        val knownHosts = Files.createTempDirectory("fileexplorer-disconnected").resolve("known_hosts").toFile()
        val repositories = listOf(
            SmbFileRepository(),
            SftpFileRepository(SftpKnownHostsStore(knownHosts, testOnly = true), DiagnosticLog()),
            FtpFileRepository(),
            WebDavFileRepository(),
        )

        repositories.forEach { repository ->
            val error = assertFailsWith<RepositoryException> {
                repository.listFiles("/").first()
            }
            assertEquals(repository.capabilities.provider, error.error.provider)
            assertEquals(RepositoryOperation.LIST, error.error.operation)
            assertEquals(RepositoryErrorKind.TRANSPORT, error.error.kind)
            assertTrue(error.error.retryable)
        }
    }

    @Test
    fun recursiveNetworkOperationsEnforceEntryAndDepthBounds() {
        val budget = NetworkTraversalBudget()
        repeat(RepositoryOperationLimits.MAX_NETWORK_TRAVERSAL_ENTRIES) {
            budget.visit(depth = 0)
        }

        assertFailsWith<IOException> { budget.visit(depth = 0) }
        assertFailsWith<IOException> {
            NetworkTraversalBudget().visit(RepositoryOperationLimits.MAX_NETWORK_TRAVERSAL_DEPTH + 1)
        }
    }
}
