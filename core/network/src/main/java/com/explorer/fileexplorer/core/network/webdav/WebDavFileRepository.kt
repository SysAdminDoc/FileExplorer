package com.explorer.fileexplorer.core.network.webdav

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.network.NetworkConnection
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class WebDavFileRepository @Inject constructor() : NetworkFileRepository {

    private var sardine: OkHttpSardine? = null
    private var baseUrl: String = ""
    private var currentConnection: NetworkConnection? = null

    override val isConnected: Boolean get() = sardine != null

    override suspend fun connect(connection: NetworkConnection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val client = OkHttpSardine()
            if (connection.username.isNotEmpty()) {
                client.setCredentials(connection.username, connection.password)
            }
            val scheme = if (connection.useTls) "https" else "http"
            baseUrl = "$scheme://${connection.host}:${connection.port}${connection.remotePath}".trimEnd('/')

            // Test connection by listing root
            client.list(baseUrl)
            sardine = client
            currentConnection = connection
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        sardine = null; currentConnection = null; baseUrl = ""
    }

    private fun resolveUrl(path: String): String {
        if (path.startsWith("http")) return path
        val cleanPath = path.trimStart('/')
        return if (cleanPath.isEmpty()) baseUrl else "$baseUrl/$cleanPath"
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val s = sardine ?: run { emit(emptyList()); return@flow }
        val items = mutableListOf<FileItem>()
        try {
            val url = resolveUrl(path)
            val resources = s.list(url)
            // First resource is the directory itself, skip it
            for (res in resources.drop(1)) {
                items.add(davResourceToFileItem(res, path))
            }
        } catch (_: Exception) {}
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext null
        try {
            val url = resolveUrl(path)
            val resources = s.list(url)
            resources.firstOrNull()?.let { davResourceToFileItem(it, path.substringBeforeLast('/')) }
        } catch (_: Exception) { null }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try { sardine?.exists(resolveUrl(path)) ?: false } catch (_: Exception) { false }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destUrl = "${resolveUrl(destination)}/$name"
                onProgress(0, 0, name)
                s.copy(resolveUrl(src), destUrl)
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destUrl = "${resolveUrl(destination)}/$name"
                onProgress(0, 0, name)
                s.move(resolveUrl(src), destUrl)
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                s.delete(resolveUrl(path))
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            sardine?.createDirectory(resolveUrl(path))
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(Exception("Created but stat failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            sardine?.move(resolveUrl(path), resolveUrl(target))
            getFileInfo(target)?.let { Result.success(it) }
                ?: Result.failure(Exception("Renamed but stat failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = sardine ?: return@withContext Result.failure(Exception("Not connected"))
            val input = s.get(resolveUrl(remotePath))
            FileOutputStream(localPath).use { output ->
                val buf = ByteArray(65536)
                var total = 0L
                var len: Int
                while (input.read(buf).also { len = it } != -1) {
                    output.write(buf, 0, len)
                    total += len
                    onProgress(total, 0)
                }
            }
            input.close()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = sardine ?: return@withContext Result.failure(Exception("Not connected"))
            val localFile = File(localPath)
            s.put(resolveUrl(remotePath), localFile, null)
            onProgress(localFile.length(), localFile.length())
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun davResourceToFileItem(res: DavResource, parentPath: String): FileItem {
        val name = res.name ?: res.href.path.trimEnd('/').substringAfterLast('/')
        val isDir = res.isDirectory
        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else res.contentType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"
        return FileItem(
            name = name, path = fullPath,
            size = res.contentLength,
            lastModified = res.modified?.time ?: res.creation?.time ?: 0L,
            isDirectory = isDir, isHidden = name.startsWith("."),
            mimeType = mime, extension = ext,
        )
    }
}
