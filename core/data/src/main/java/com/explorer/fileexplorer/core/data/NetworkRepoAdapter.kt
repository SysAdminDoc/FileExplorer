package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface NetworkRepoProvider {
    fun deleteCapabilities(paths: List<String>): DeleteCapabilities = DeleteCapabilities.PROVIDER_DELETE_ONLY
    fun listFiles(path: String): Flow<List<FileItem>>
    suspend fun getFileInfo(path: String): FileItem?
    suspend fun exists(path: String): Boolean
    suspend fun copyFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, onProgress: (Long, Long, String) -> Unit): Result<Int>
    suspend fun moveFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, onProgress: (Long, Long, String) -> Unit): Result<Int>
    suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int>
    suspend fun createDirectory(path: String): Result<FileItem>
    suspend fun rename(path: String, newName: String): Result<FileItem>
}

class NetworkRepoAdapter(private val delegate: NetworkRepoProvider) : FileRepository {
    override fun deleteCapabilities(paths: List<String>): DeleteCapabilities = delegate.deleteCapabilities(paths)
    override fun listFiles(path: String): Flow<List<FileItem>> = delegate.listFiles(path)
    override suspend fun getFileInfo(path: String): FileItem? = delegate.getFileInfo(path)
    override suspend fun exists(path: String): Boolean = delegate.exists(path)
    override suspend fun copyFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, onProgress: (Long, Long, String) -> Unit): Result<Int> =
        delegate.copyFiles(sources, destination, conflictResolution, onProgress)
    override suspend fun moveFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, onProgress: (Long, Long, String) -> Unit): Result<Int> =
        delegate.moveFiles(sources, destination, conflictResolution, onProgress)
    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> =
        delegate.deleteFiles(paths, onProgress)
    override suspend fun createDirectory(path: String): Result<FileItem> = delegate.createDirectory(path)
    override suspend fun createFile(path: String): Result<FileItem> = Result.failure(UnsupportedOperationException("Not supported on network"))
    override suspend fun rename(path: String, newName: String): Result<FileItem> = delegate.rename(path, newName)
    override suspend fun calculateSize(paths: List<String>): Long = 0L
    override fun search(rootPath: String, query: String, regex: Boolean, includeHidden: Boolean): Flow<FileItem> = emptyFlow()
    override suspend fun getChecksum(path: String, algorithm: String): String = ""
}

fun interface SchemeResolver {
    fun resolve(scheme: String): NetworkRepoProvider?
}
