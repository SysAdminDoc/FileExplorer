package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.model.repositoryException
import com.explorer.fileexplorer.plugin.PluginDescriptor
import com.explorer.fileexplorer.plugin.PluginFileCodec
import com.explorer.fileexplorer.plugin.PluginManager
import com.explorer.fileexplorer.plugin.PluginRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** FileRepository adapter for a plugin-backed URI scheme. */
class PluginFileRepository(
    private val pluginManager: PluginManager,
    private val descriptor: PluginDescriptor,
) : FileRepository {

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.local("plugin:${descriptor.id}")

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        emit(PluginFileCodec.entries(execute(RepositoryOperation.LIST, PluginRequests.list(path))))
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? =
        PluginFileCodec.item(execute(RepositoryOperation.INFO, PluginRequests.info(path)))

    override suspend fun exists(path: String): Boolean =
        execute(RepositoryOperation.EXISTS, PluginRequests.exists(path)).let { response ->
            if (!response.containsKey("exists")) {
                throw repositoryException(
                    capabilities.provider,
                    RepositoryOperation.EXISTS,
                    RepositoryErrorKind.CORRUPT,
                    "plugin response omitted the exists field",
                    retryable = false,
                )
            }
            response.getBoolean("exists")
        }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = result(RepositoryOperation.COPY) {
        val response = execute(RepositoryOperation.COPY, PluginRequests.copy(sources, destination, conflictResolution, conflictSuffix))
        onProgress(response.getLong("bytes", -1L), response.getLong("total_bytes", -1L), "")
        response.getInt("count", -1).takeIf { it >= 0 } ?: error("Plugin did not return a copy count")
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = result(RepositoryOperation.MOVE) {
        val response = execute(RepositoryOperation.MOVE, PluginRequests.move(sources, destination, conflictResolution, conflictSuffix))
        onProgress(response.getLong("bytes", -1L), response.getLong("total_bytes", -1L), "")
        response.getInt("count", -1).takeIf { it >= 0 } ?: error("Plugin did not return a move count")
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = result(RepositoryOperation.DELETE) {
        val response = execute(RepositoryOperation.DELETE, PluginRequests.delete(paths))
        paths.forEach(onProgress)
        response.getInt("count", -1).takeIf { it >= 0 } ?: error("Plugin did not return a delete count")
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = result(RepositoryOperation.CREATE_DIRECTORY) {
        PluginFileCodec.item(execute(RepositoryOperation.CREATE_DIRECTORY, PluginRequests.createDirectory(path)))
            ?: error("Plugin did not return a directory")
    }

    override suspend fun createFile(path: String): Result<FileItem> = result(RepositoryOperation.CREATE_FILE) {
        PluginFileCodec.item(execute(RepositoryOperation.CREATE_FILE, PluginRequests.createFile(path)))
            ?: error("Plugin did not return a file")
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = result(RepositoryOperation.RENAME) {
        PluginFileCodec.item(execute(RepositoryOperation.RENAME, PluginRequests.rename(path, newName)))
            ?: error("Plugin did not return the renamed item")
    }

    override suspend fun calculateSize(paths: List<String>): Long =
        execute(RepositoryOperation.SIZE, PluginRequests.size(paths)).getLong("size", -1L).takeIf { it >= 0 }
            ?: throw repositoryException(
                capabilities.provider,
                RepositoryOperation.SIZE,
                RepositoryErrorKind.CORRUPT,
                "plugin response omitted a valid size",
                retryable = false,
            )

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        val response = execute(RepositoryOperation.SEARCH, PluginRequests.search(rootPath, query, regex, includeHidden))
        PluginFileCodec.entries(response).forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String =
        execute(RepositoryOperation.CHECKSUM, PluginRequests.checksum(path, algorithm)).getString("checksum")
            ?: throw repositoryException(
                capabilities.provider,
                RepositoryOperation.CHECKSUM,
                RepositoryErrorKind.CORRUPT,
                "plugin response omitted a checksum",
                retryable = false,
            )

    private suspend fun execute(operation: RepositoryOperation, request: android.os.Bundle): android.os.Bundle = try {
        withContext(Dispatchers.IO) { pluginManager.execute(descriptor, request) }
    } catch (error: Throwable) {
        throw error.asRepositoryException(capabilities.provider, operation)
    }

    private suspend fun <T> result(operation: RepositoryOperation, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error.asRepositoryException(capabilities.provider, operation, RepositoryErrorKind.CORRUPT))
    }
}
