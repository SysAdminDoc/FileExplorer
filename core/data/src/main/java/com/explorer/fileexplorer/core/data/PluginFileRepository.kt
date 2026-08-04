package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
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

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        emit(PluginFileCodec.entries(execute(PluginRequests.list(path))))
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? =
        PluginFileCodec.item(execute(PluginRequests.info(path)))

    override suspend fun exists(path: String): Boolean =
        execute(PluginRequests.exists(path)).getBoolean("exists", false)

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = result {
        val response = execute(PluginRequests.copy(sources, destination, conflictResolution))
        onProgress(response.getLong("bytes", -1L), response.getLong("total_bytes", -1L), "")
        response.getInt("count", 0)
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = result {
        val response = execute(PluginRequests.move(sources, destination, conflictResolution))
        onProgress(response.getLong("bytes", -1L), response.getLong("total_bytes", -1L), "")
        response.getInt("count", 0)
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = result {
        val response = execute(PluginRequests.delete(paths))
        paths.forEach(onProgress)
        response.getInt("count", paths.size)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = result {
        PluginFileCodec.item(execute(PluginRequests.createDirectory(path)))
            ?: error("Plugin did not return a directory")
    }

    override suspend fun createFile(path: String): Result<FileItem> = result {
        PluginFileCodec.item(execute(PluginRequests.createFile(path)))
            ?: error("Plugin did not return a file")
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = result {
        PluginFileCodec.item(execute(PluginRequests.rename(path, newName)))
            ?: error("Plugin did not return the renamed item")
    }

    override suspend fun calculateSize(paths: List<String>): Long =
        execute(PluginRequests.size(paths)).getLong("size", 0L)

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        val response = execute(PluginRequests.search(rootPath, query, regex, includeHidden))
        PluginFileCodec.entries(response).forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String =
        execute(PluginRequests.checksum(path, algorithm)).getString("checksum").orEmpty()

    private suspend fun execute(request: android.os.Bundle): android.os.Bundle = withContext(Dispatchers.IO) {
        pluginManager.execute(descriptor, request)
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
