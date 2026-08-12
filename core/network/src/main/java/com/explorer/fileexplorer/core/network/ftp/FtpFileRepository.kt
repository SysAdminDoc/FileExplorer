package com.explorer.fileexplorer.core.network.ftp

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictNamePolicy
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
import com.explorer.fileexplorer.core.model.unsupportedRepositoryOperation
import com.explorer.fileexplorer.core.network.NetworkConnection
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import com.explorer.fileexplorer.core.network.NetworkTraversalBudget
import com.explorer.fileexplorer.core.network.checkedNetworkAdd
import com.explorer.fileexplorer.core.network.checksumDigest
import com.explorer.fileexplorer.core.network.checksumHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

class FtpFileRepository @Inject constructor() : NetworkFileRepository {

    override val capabilities: RepositoryCapabilities
        get() = RepositoryCapabilities.network(
            provider = if (currentConnection?.useTls == true) "ftps" else "ftp",
            serverSideCopy = false,
            advancedOperations = true,
        )

    private var client: FTPClient? = null
    private var currentConnection: NetworkConnection? = null

    override val isConnected: Boolean get() = client?.isConnected == true

    override suspend fun connect(connection: NetworkConnection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val ftp = if (connection.useTls) FTPSClient(true) else FTPClient()
            ftp.connectTimeout = 15000
            ftp.defaultTimeout = 15000
            ftp.connect(connection.host, connection.port)
            ftp.login(connection.username.ifEmpty { "anonymous" }, connection.password.ifEmpty { "anonymous@" })
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.bufferSize = 65536

            if (connection.remotePath.isNotEmpty() && connection.remotePath != "/") {
                ftp.changeWorkingDirectory(connection.remotePath)
            }

            client = ftp
            currentConnection = connection
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CONNECT))
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.let { if (it.isConnected) { it.logout(); it.disconnect() } }
        } catch (_: Exception) {}
        client = null; currentConnection = null
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        val items = mutableListOf<FileItem>()
        try {
            val entries = ftp.listFiles(path)
            for (entry in entries) {
                if (entry.name == "." || entry.name == "..") continue
                items.add(ftpFileToFileItem(entry, path))
            }
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        try {
            val files = ftp.listFiles(path)
            files.firstOrNull()?.let { ftpFileToFileItem(it, path.substringBeforeLast('/')) }
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) null
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
            ftp.listFiles(path).isNotEmpty()
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) false
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        // FTP has no server-side copy. Would need download+upload.
        Result.failure(unsupportedRepositoryOperation(capabilities.provider, RepositoryOperation.COPY))
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.MOVE),
        )
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val targetName = resolveFtpTarget(ftp, destination, name, conflictResolution, conflictSuffix)
                if (targetName == null) {
                    count++
                    continue
                }
                val dest = "${destination.trimEnd('/')}/$targetName"
                val size = ftpSize(ftp, src, NetworkTraversalBudget())
                onProgress(0, size, name)
                if (!ftp.rename(src, dest)) throw IOException("FTP rename failed: $src -> $dest")
                onProgress(size, size, name)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DELETE),
        )
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                val files = ftp.listFiles(path)
                if (files.isNotEmpty() && files.first().isDirectory) {
                    deleteFtpRecursive(ftp, path)
                } else if (!ftp.deleteFile(path)) {
                    throw IOException("FTP delete failed: $path")
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
    }

    private fun deleteFtpRecursive(ftp: FTPClient, path: String) {
        val entries = ftp.listFiles(path)
        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val fullPath = "$path/${entry.name}"
            if (entry.isDirectory) deleteFtpRecursive(ftp, fullPath)
            else if (!ftp.deleteFile(fullPath)) throw IOException("FTP delete failed: $fullPath")
        }
        if (!ftp.removeDirectory(path)) throw IOException("FTP directory delete failed: $path")
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY),
            )
            if (ftp.makeDirectory(path)) {
                getFileInfo(path)?.let { Result.success(it) }
                    ?: Result.failure(repositoryException(
                        capabilities.provider,
                        RepositoryOperation.CREATE_DIRECTORY,
                        RepositoryErrorKind.TRANSPORT,
                        "created but metadata could not be read",
                        retryable = true,
                    ))
            } else Result.failure(repositoryException(
                capabilities.provider,
                RepositoryOperation.CREATE_DIRECTORY,
                RepositoryErrorKind.TRANSPORT,
                "mkdir failed",
                retryable = true,
            ))
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY))
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.RENAME),
            )
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            if (ftp.rename(path, target)) {
                getFileInfo(target)?.let { Result.success(it) }
                    ?: Result.failure(repositoryException(
                        capabilities.provider,
                        RepositoryOperation.RENAME,
                        RepositoryErrorKind.TRANSPORT,
                        "renamed but metadata could not be read",
                        retryable = true,
                    ))
            } else Result.failure(repositoryException(
                capabilities.provider,
                RepositoryOperation.RENAME,
                RepositoryErrorKind.TRANSPORT,
                "rename failed",
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
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD),
            )
            val expectedSize = ftpFileSize(ftp, remotePath)
            onProgress(0, expectedSize)
            val input = ftp.retrieveFileStream(remotePath)
                ?: throw IOException("FTP server did not open the download source")
            FileOutputStream(localPath).use { output ->
                input.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total, expectedSize)
                    }
                }
            }
            if (!ftp.completePendingCommand()) throw IOException("FTP download did not complete")
            if (File(localPath).length() != expectedSize) throw IOException("FTP download size mismatch")
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
            val ftp = client ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD),
            )
            val localFile = File(localPath)
            val totalSize = localFile.length()
            onProgress(0, totalSize)
            val output = ftp.storeFileStream(remotePath)
                ?: throw IOException("FTP server did not open the upload destination")
            FileInputStream(localFile).use { input ->
                output.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        stream.write(buffer, 0, read)
                        written += read
                        onProgress(written, totalSize)
                    }
                }
            }
            if (!ftp.completePendingCommand()) throw IOException("FTP upload did not complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SIZE)
        try {
            val budget = NetworkTraversalBudget()
            paths.fold(0L) { total, path ->
                currentCoroutineContext().ensureActive()
                checkedNetworkAdd(total, ftpSize(ftp, path, budget))
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
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
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
                for (entry in ftp.listFiles(path)) {
                    currentCoroutineContext().ensureActive()
                    if (entry.name == "." || entry.name == ".." || entry.isSymbolicLink) continue
                    val item = ftpFileToFileItem(entry, path)
                    val matches = if (matcher != null) matcher.containsMatchIn(item.name)
                    else item.name.contains(query, ignoreCase = true)
                    if (matches && (includeHidden || !item.isHidden)) {
                        resultCount++
                        if (resultCount > RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS) {
                            throw IOException("Remote search exceeded ${RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS} results")
                        }
                        emit(item)
                    }
                    if (entry.isDirectory) pending.addLast(ftpChildPath(path, entry.name) to (depth + 1))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val ftp = client ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        try {
            val expectedSize = ftpFileSize(ftp, path)
            if (expectedSize > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
            }
            val digest = checksumDigest(algorithm)
            val input = ftp.retrieveFileStream(path) ?: throw IOException("FTP server did not open the checksum source")
            var readBytes = 0L
            input.use { stream ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    readBytes += read
                    if (readBytes > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                        throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
                    }
                    digest.update(buffer, 0, read)
                }
            }
            if (!ftp.completePendingCommand()) throw IOException("FTP checksum transfer did not complete")
            if (readBytes != expectedSize) throw IOException("FTP checksum source changed during download")
            checksumHex(digest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        }
    }

    private fun ftpSize(
        ftp: FTPClient,
        path: String,
        budget: NetworkTraversalBudget,
        depth: Int = 0,
        visitedDirectories: MutableSet<String> = mutableSetOf(),
    ): Long {
        budget.visit(depth)
        val entries = ftp.listFiles(path)
        val requestedName = path.trimEnd('/').substringAfterLast('/')
        if (entries.size == 1 && entries[0].name == requestedName && !entries[0].isDirectory) {
            return entries[0].size.coerceAtLeast(0L)
        }
        if (!visitedDirectories.add(path)) throw IOException("FTP directory cycle detected: $path")
        try {
            if (entries.isEmpty() && requestedName.isNotEmpty()) throw IOException("FTP path not found: $path")
            return entries.asSequence()
                .filterNot { it.name == "." || it.name == ".." || it.isSymbolicLink }
                .fold(0L) { total, entry ->
                    val childSize = if (entry.isDirectory) {
                        ftpSize(ftp, ftpChildPath(path, entry.name), budget, depth + 1, visitedDirectories)
                    } else entry.size.coerceAtLeast(0L)
                    checkedNetworkAdd(total, childSize)
                }
        } finally {
            visitedDirectories.remove(path)
        }
    }

    private fun ftpFileSize(ftp: FTPClient, path: String): Long {
        val entries = ftp.listFiles(path)
        val requestedName = path.trimEnd('/').substringAfterLast('/')
        val file = entries.firstOrNull { it.name == requestedName && !it.isDirectory }
            ?: throw IOException("FTP path is not a regular file: $path")
        return file.size.coerceAtLeast(0L)
    }

    private fun ftpChildPath(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"

    private fun resolveFtpTarget(
        ftp: FTPClient,
        destination: String,
        sourceName: String,
        resolution: ConflictResolution,
        conflictSuffix: String?,
    ): String? {
        fun exists(name: String): Boolean = ftp.listFiles("${destination.trimEnd('/')}/$name").isNotEmpty()
        if (!exists(sourceName)) return sourceName
        return when (resolution) {
            ConflictResolution.SKIP -> null
            ConflictResolution.OVERWRITE -> sourceName
            ConflictResolution.RENAME,
            ConflictResolution.ASK,
            -> {
                val deterministic = conflictSuffix?.let { ConflictNamePolicy.fileName(sourceName, it) }
                var candidate = deterministic ?: numberedName(sourceName, 1)
                var index = 1
                while (exists(candidate)) {
                    index++
                    candidate = numberedName(deterministic ?: sourceName, index)
                }
                candidate
            }
        }
    }

    private fun numberedName(name: String, number: Int): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return "$base ($number)$extension"
    }

    private fun ftpFileToFileItem(file: FTPFile, parentPath: String): FileItem {
        val name = file.name
        val isDir = file.isDirectory
        val isLink = file.isSymbolicLink
        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"
        return FileItem(
            name = name, path = fullPath,
            size = file.size,
            lastModified = file.timestamp?.timeInMillis ?: 0L,
            isDirectory = isDir, isHidden = name.startsWith("."),
            isSymlink = isLink, mimeType = mime, extension = ext,
            ownerName = file.user, groupName = file.group,
        )
    }
}
