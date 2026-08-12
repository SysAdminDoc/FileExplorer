package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.model.mapRepositoryFailure
import com.explorer.fileexplorer.core.model.unsupportedRepositoryOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

interface NetworkRepoProvider {
    val capabilities: RepositoryCapabilities
        get() = RepositoryCapabilities.unsupported("network")

    fun deleteCapabilities(paths: List<String>): DeleteCapabilities = DeleteCapabilities.PROVIDER_DELETE_ONLY
    fun listFiles(path: String): Flow<List<FileItem>>
    suspend fun getFileInfo(path: String): FileItem?
    suspend fun exists(path: String): Boolean
    suspend fun copyFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, conflictSuffix: String? = null, onProgress: (Long, Long, String) -> Unit): Result<Int>
    suspend fun moveFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, conflictSuffix: String? = null, onProgress: (Long, Long, String) -> Unit): Result<Int>
    suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int>
    suspend fun createDirectory(path: String): Result<FileItem>
    suspend fun rename(path: String, newName: String): Result<FileItem>

    suspend fun calculateSize(paths: List<String>): Long =
        throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.SIZE)

    fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.SEARCH)
    }

    suspend fun getChecksum(path: String, algorithm: String): String =
        throw unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.CHECKSUM)
}

class NetworkRepoAdapter(private val delegate: NetworkRepoProvider) : FileRepository {
    override val capabilities: RepositoryCapabilities
        get() = delegate.capabilities

    override fun deleteCapabilities(paths: List<String>): DeleteCapabilities = delegate.deleteCapabilities(paths)
    override fun listFiles(path: String): Flow<List<FileItem>> = delegate.listFiles(path).catch { error ->
        throw error.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
    }

    override suspend fun getFileInfo(path: String): FileItem? = call(RepositoryOperation.INFO) {
        delegate.getFileInfo(path)
    }

    override suspend fun exists(path: String): Boolean = call(RepositoryOperation.EXISTS) {
        delegate.exists(path)
    }

    override suspend fun copyFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, conflictSuffix: String?, onProgress: (Long, Long, String) -> Unit): Result<Int> =
        delegate.copyFiles(sources, destination, conflictResolution, conflictSuffix, onProgress)
            .mapRepositoryFailure(capabilities.provider, RepositoryOperation.COPY)

    override suspend fun moveFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, conflictSuffix: String?, onProgress: (Long, Long, String) -> Unit): Result<Int> =
        delegate.moveFiles(sources, destination, conflictResolution, conflictSuffix, onProgress)
            .mapRepositoryFailure(capabilities.provider, RepositoryOperation.MOVE)

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> =
        delegate.deleteFiles(paths, onProgress)
            .mapRepositoryFailure(capabilities.provider, RepositoryOperation.DELETE)

    override suspend fun createDirectory(path: String): Result<FileItem> =
        delegate.createDirectory(path)
            .mapRepositoryFailure(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY)

    override suspend fun createFile(path: String): Result<FileItem> =
        Result.failure(unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.CREATE_FILE))

    override suspend fun rename(path: String, newName: String): Result<FileItem> =
        delegate.rename(path, newName)
            .mapRepositoryFailure(capabilities.provider, RepositoryOperation.RENAME)

    override suspend fun calculateSize(paths: List<String>): Long = call(RepositoryOperation.SIZE) {
        delegate.calculateSize(paths)
    }

    override fun search(rootPath: String, query: String, regex: Boolean, includeHidden: Boolean): Flow<FileItem> = flow {
        try {
            delegate.search(rootPath, query, regex, includeHidden).collect { emit(it) }
        } catch (error: Throwable) {
            throw error.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
        }
    }

    override suspend fun getChecksum(path: String, algorithm: String): String =
        call(RepositoryOperation.CHECKSUM) { delegate.getChecksum(path, algorithm) }

    private suspend fun <T> call(operation: RepositoryOperation, block: suspend () -> T): T = try {
        block()
    } catch (error: Throwable) {
        throw error.asRepositoryException(capabilities.provider, operation)
    }
}

fun interface SchemeResolver {
    fun resolve(scheme: String): NetworkRepoProvider?
}
