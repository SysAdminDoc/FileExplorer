package com.explorer.fileexplorer.core.network.webdav

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperationLimits
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.model.isMissingRepositoryResource
import com.explorer.fileexplorer.core.model.notConnectedRepositoryException
import com.explorer.fileexplorer.core.model.repositoryException
import com.explorer.fileexplorer.core.network.NetworkConnection
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import com.explorer.fileexplorer.core.network.NetworkTraversalBudget
import com.explorer.fileexplorer.core.network.checkedNetworkAdd
import com.explorer.fileexplorer.core.network.checksumDigest
import com.explorer.fileexplorer.core.network.checksumHex
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

class WebDavFileRepository @Inject constructor() : NetworkFileRepository {

    override val capabilities: RepositoryCapabilities
        get() = RepositoryCapabilities.network(
            provider = if (currentConnection?.useTls == true) "webdavs" else "webdav",
            advancedOperations = true,
        )

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
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CONNECT))
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
        val s = sardine ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        val items = mutableListOf<FileItem>()
        try {
            val url = resolveUrl(path)
            val resources = s.list(url)
            // First resource is the directory itself, skip it
            for (res in resources.drop(1)) {
                items.add(davResourceToFileItem(res, path))
            }
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val s = sardine ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        try {
            val url = resolveUrl(path)
            val resources = s.list(url)
            resources.firstOrNull()?.let { davResourceToFileItem(it, path.substringBeforeLast('/')) }
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) null
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val s = sardine ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        try {
            s.exists(resolveUrl(path))
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) false
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.COPY),
        )
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destUrl = "${resolveUrl(destination)}/$name"
                val size = calculateSize(listOf(src))
                onProgress(0, size, name)
                s.copy(resolveUrl(src), destUrl)
                onProgress(size, size, name)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.COPY))
        }
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.MOVE),
        )
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destUrl = "${resolveUrl(destination)}/$name"
                val size = calculateSize(listOf(src))
                onProgress(0, size, name)
                s.move(resolveUrl(src), destUrl)
                onProgress(size, size, name)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val s = sardine ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DELETE),
        )
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                s.delete(resolveUrl(path))
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val s = sardine ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY),
            )
            s.createDirectory(resolveUrl(path))
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(repositoryException(
                    capabilities.provider,
                    RepositoryOperation.CREATE_DIRECTORY,
                    RepositoryErrorKind.TRANSPORT,
                    "created but metadata could not be read",
                    retryable = true,
                ))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY))
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val s = sardine ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.RENAME),
            )
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            s.move(resolveUrl(path), resolveUrl(target))
            getFileInfo(target)?.let { Result.success(it) }
                ?: Result.failure(repositoryException(
                    capabilities.provider,
                    RepositoryOperation.RENAME,
                    RepositoryErrorKind.TRANSPORT,
                    "renamed but metadata could not be read",
                    retryable = true,
                ))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.RENAME))
        }
    }

    override suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = sardine ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD),
            )
            val expectedSize = getFileInfo(remotePath)?.size?.coerceAtLeast(0L)
                ?: throw IOException("WebDAV download source was not found: $remotePath")
            onProgress(0, expectedSize)
            val input = s.get(resolveUrl(remotePath))
            FileOutputStream(localPath).use { output ->
                val buf = ByteArray(65536)
                var total = 0L
                var len: Int
                while (input.read(buf).also { len = it } != -1) {
                    output.write(buf, 0, len)
                    total += len
                    onProgress(total, expectedSize)
                }
            }
            input.close()
            if (File(localPath).length() != expectedSize) throw IOException("WebDAV download size mismatch")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD))
        }
    }

    override suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = sardine ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD),
            )
            val localFile = File(localPath)
            s.put(resolveUrl(remotePath), localFile, null)
            onProgress(localFile.length(), localFile.length())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        val s = sardine ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SIZE)
        try {
            val budget = NetworkTraversalBudget()
            paths.fold(0L) { total, path ->
                currentCoroutineContext().ensureActive()
                checkedNetworkAdd(total, webDavSize(s, path, budget))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SIZE)
        }
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> = flow {
        val s = sardine ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
        try {
            val matcher = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null
            val pending = ArrayDeque<Pair<String, Int>>()
            val budget = NetworkTraversalBudget()
            var resultCount = 0
            pending.addLast(rootPath to 0)
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (path, depth) = pending.removeLast()
                budget.visit(depth)
                val resources = s.list(resolveUrl(path))
                for (resource in resources.drop(1)) {
                    currentCoroutineContext().ensureActive()
                    val item = davResourceToFileItem(resource, path)
                    val matches = if (matcher != null) matcher.containsMatchIn(item.name)
                    else item.name.contains(query, ignoreCase = true)
                    if (matches && (includeHidden || !item.isHidden)) {
                        resultCount++
                        if (resultCount > RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS) {
                            throw IOException("Remote search exceeded ${RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS} results")
                        }
                        emit(item)
                    }
                    if (item.isDirectory) pending.addLast(item.path to (depth + 1))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val s = sardine ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        try {
            val expectedSize = getFileInfo(path)?.size?.coerceAtLeast(0L)
                ?: throw IOException("WebDAV checksum source was not found: $path")
            if (expectedSize > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
            }
            val digest = checksumDigest(algorithm)
            s.get(resolveUrl(path)).use { input ->
                val buffer = ByteArray(64 * 1024)
                var readBytes = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    readBytes += read
                    if (readBytes > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                        throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
                    }
                    digest.update(buffer, 0, read)
                }
                if (readBytes != expectedSize) throw IOException("WebDAV checksum source changed during read")
            }
            checksumHex(digest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        }
    }

    private fun webDavSize(
        sardine: OkHttpSardine,
        path: String,
        budget: NetworkTraversalBudget,
        depth: Int = 0,
        visitedDirectories: MutableSet<String> = mutableSetOf(),
    ): Long {
        budget.visit(depth)
        val resources = sardine.list(resolveUrl(path))
        val self = resources.firstOrNull()
        if (self != null && !self.isDirectory) return self.contentLength.coerceAtLeast(0L)
        if (!visitedDirectories.add(path)) throw IOException("WebDAV directory cycle detected: $path")
        try {
            return resources.drop(1)
                .fold(0L) { total, resource ->
                    checkedNetworkAdd(
                        total,
                        webDavSize(sardine, davResourcePath(path, resource), budget, depth + 1, visitedDirectories),
                    )
                }
        } finally {
            visitedDirectories.remove(path)
        }
    }

    private fun davResourcePath(parent: String, resource: DavResource): String =
        davResourceToFileItem(resource, parent).path

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
