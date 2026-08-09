package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryException
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.httpRepositoryException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RepositoryContractTest {
    @Test
    fun adapterExposesDelegateCapabilities() {
        val adapter = NetworkRepoAdapter(FakeProvider())

        assertEquals("test-network", adapter.capabilities.provider)
        assertEquals(false, adapter.capabilities.supports(RepositoryOperation.CREATE_FILE))
        assertEquals(true, adapter.capabilities.supports(RepositoryOperation.LIST))
    }

    @Test
    fun unsupportedAdapterOperationsAreTyped() = runBlocking {
        val adapter = NetworkRepoAdapter(FakeProvider())

        val error = adapter.createFile("remote://file").exceptionOrNull() as? RepositoryException
        assertNotNull(error)

        assertEquals("test-network", error.error.provider)
        assertEquals(RepositoryOperation.CREATE_FILE, error.error.operation)
        assertEquals(RepositoryErrorKind.UNSUPPORTED, error.error.kind)
        assertEquals(false, error.error.retryable)
    }

    @Test
    fun unsupportedFlowAndSizeOperationsDoNotLookLikeEmptyResults() = runBlocking {
        val adapter = NetworkRepoAdapter(FakeProvider())

        val searchError = assertFailsWith<RepositoryException> {
            adapter.search("remote://", "needle").first()
        }
        val sizeError = assertFailsWith<RepositoryException> {
            adapter.calculateSize(listOf("remote://file"))
        }

        assertEquals(RepositoryOperation.SEARCH, searchError.error.operation)
        assertEquals(RepositoryOperation.SIZE, sizeError.error.operation)
        assertEquals(RepositoryErrorKind.UNSUPPORTED, searchError.error.kind)
    }

    @Test
    fun delegateFailuresAreScopedToTheOperation() = runBlocking {
        val adapter = NetworkRepoAdapter(FakeProvider(failList = true))

        val error = assertFailsWith<RepositoryException> {
            adapter.listFiles("remote://").first()
        }

        assertEquals("test-network", error.error.provider)
        assertEquals(RepositoryOperation.LIST, error.error.operation)
        assertEquals(RepositoryErrorKind.TRANSPORT, error.error.kind)
    }

    @Test
    fun httpErrorsPreserveStatusAndRetryability() {
        val unauthorized = httpRepositoryException("cloud", RepositoryOperation.LIST, 401)
        val unavailable = httpRepositoryException("cloud", RepositoryOperation.LIST, 503)

        assertEquals(RepositoryErrorKind.AUTHENTICATION, unauthorized.error.kind)
        assertEquals(401, unauthorized.error.statusCode)
        assertEquals(false, unauthorized.error.retryable)
        assertEquals(RepositoryErrorKind.TRANSPORT, unavailable.error.kind)
        assertEquals(true, unavailable.error.retryable)
    }

    private class FakeProvider(
        private val failList: Boolean = false,
    ) : NetworkRepoProvider {
        override val capabilities: RepositoryCapabilities = RepositoryCapabilities.network(
            provider = "test-network",
            serverSideCopy = false,
        )

        override fun listFiles(path: String): Flow<List<FileItem>> = if (failList) {
            flow { throw java.io.IOException("connection reset") }
        } else {
            emptyFlow()
        }

        override suspend fun getFileInfo(path: String): FileItem? = null
        override suspend fun exists(path: String): Boolean = false
        override suspend fun copyFiles(
            sources: List<String>,
            destination: String,
            conflictResolution: ConflictResolution,
            onProgress: (Long, Long, String) -> Unit,
        ): Result<Int> = Result.success(0)

        override suspend fun moveFiles(
            sources: List<String>,
            destination: String,
            conflictResolution: ConflictResolution,
            onProgress: (Long, Long, String) -> Unit,
        ): Result<Int> = Result.success(0)

        override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> =
            Result.success(0)

        override suspend fun createDirectory(path: String): Result<FileItem> =
            Result.failure(UnsupportedOperationException())

        override suspend fun rename(path: String, newName: String): Result<FileItem> =
            Result.failure(UnsupportedOperationException())
    }
}
