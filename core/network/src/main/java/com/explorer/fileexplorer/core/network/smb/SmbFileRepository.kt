package com.explorer.fileexplorer.core.network.smb

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
import com.explorer.fileexplorer.core.network.NetworkConnection
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import com.explorer.fileexplorer.core.network.NetworkTraversalBudget
import com.explorer.fileexplorer.core.network.checkedNetworkAdd
import com.explorer.fileexplorer.core.network.checksumDigest
import com.explorer.fileexplorer.core.network.checksumHex
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SmbFileRepository @Inject constructor() : NetworkFileRepository {

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.network(
        provider = "smb",
        advancedOperations = true,
    )

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var currentConnection: NetworkConnection? = null

    override val isConnected: Boolean get() = share != null && session?.connection?.isConnected == true

    override suspend fun connect(connection: NetworkConnection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val config = SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .build()
            client = SMBClient(config)
            this@SmbFileRepository.connection = client!!.connect(connection.host, connection.port)

            val domain = if (connection.username.contains("\\")) connection.username.substringBefore("\\") else ""
            val user = if (connection.username.contains("\\")) connection.username.substringAfter("\\") else connection.username

            val authCtx = if (user.isNotEmpty()) {
                AuthenticationContext(user, connection.password.toCharArray(), domain)
            } else {
                AuthenticationContext.guest()
            }

            session = this@SmbFileRepository.connection!!.authenticate(authCtx)
            share = session!!.connectShare(connection.shareName) as DiskShare
            currentConnection = connection
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e.asRepositoryException("smb", RepositoryOperation.CONNECT))
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null; session = null; connection = null; client = null; currentConnection = null
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val s = share ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        val smbPath = normalizePath(path)
        val items = mutableListOf<FileItem>()
        try {
            val entries = s.list(smbPath)
            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue
                items.add(smbEntryToFileItem(entry, smbPath))
            }
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val s = share ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        try {
            val info = s.getFileInformation(normalizePath(path))
            val name = path.trimEnd('/').substringAfterLast('/')
            val basicInfo = info.basicInformation
            val stdInfo = info.standardInformation
            val isDir = stdInfo.isDirectory
            val ext = name.substringAfterLast('.', "")
            val mime = if (isDir) "inode/directory"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            FileItem(
                name = name, path = path,
                size = stdInfo.endOfFile,
                lastModified = basicInfo.lastWriteTime.toEpochMillis(),
                isDirectory = isDir, isHidden = name.startsWith("."),
                mimeType = mime, extension = ext,
            )
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource()) null
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val s = share ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        try {
            s.fileExists(normalizePath(path)) || s.folderExists(normalizePath(path))
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
        // SMB copy: read from source, write to destination on same share
        var count = 0
        try {
            val s = share ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.COPY),
            )
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val targetName = resolveSmbTarget(s, destination, name, conflictResolution, conflictSuffix)
                if (targetName == null) {
                    count++
                    continue
                }
                val destPath = "${normalizePath(destination)}\\$targetName"
                onProgress(0, 0, name)

                val sourcePath = normalizePath(src)
                val sourceSize = s.getFileInformation(sourcePath).standardInformation.endOfFile
                val srcFile = s.openFile(sourcePath,
                    EnumSet.of(AccessMask.GENERIC_READ), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN, null)

                val dstFile = s.openFile(destPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null)

                srcFile.use { source ->
                    dstFile.use { destinationFile ->
                        onProgress(0, sourceSize, name)
                        try {
                            // SMBJ maps this to FSCTL_SRV_COPYCHUNK so the server copies
                            // within the share without sending file bytes through Android.
                            source.remoteCopyTo(destinationFile)
                            onProgress(sourceSize, sourceSize, name)
                        } catch (_: Exception) {
                            // Older NAS firmware may reject FSCTL_SRV_COPYCHUNK. Preserve
                            // compatibility by falling back to the existing streamed copy.
                            source.inputStream.use { input ->
                                destinationFile.outputStream.use { output ->
                                    val buf = ByteArray(65536)
                                    var totalRead = 0L
                                    var len: Int
                                    while (input.read(buf).also { len = it } != -1) {
                                        output.write(buf, 0, len)
                                        totalRead += len
                                        onProgress(totalRead, sourceSize, name)
                                    }
                                }
                            }
                        }
                    }
                }
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
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = share ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.MOVE),
        )
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val targetName = resolveSmbTarget(s, destination, name, conflictResolution, conflictSuffix)
                if (targetName == null) {
                    count++
                    continue
                }
                val destPath = "${normalizePath(destination)}\\$targetName"
                val sourceSize = smbSize(s, src, NetworkTraversalBudget())
                onProgress(0, sourceSize, name)
                s.openFile(normalizePath(src),
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN, null).use { f ->
                    f.rename(destPath)
                }
                onProgress(sourceSize, sourceSize, name)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val s = share ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DELETE),
        )
        var count = 0
        try {
            for (path in paths) {
                val p = normalizePath(path)
                onProgress(path.substringAfterLast('/'))
                if (s.folderExists(p)) s.rmdir(p, true) else s.rm(p)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val s = share ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY),
            )
            s.mkdir(normalizePath(path))
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
        val s = share ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.RENAME),
        )
        try {
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            s.openFile(normalizePath(path),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_DELETE),
                SMB2CreateDisposition.FILE_OPEN, null).use { f ->
                f.rename(normalizePath(target))
            }
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
            val s = share ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD),
            )
            val file = s.openFile(normalizePath(remotePath),
                EnumSet.of(AccessMask.GENERIC_READ), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN, null)
            val totalSize = s.getFileInformation(normalizePath(remotePath)).standardInformation.endOfFile
            file.inputStream.use { input ->
                FileOutputStream(localPath).use { output ->
                    val buf = ByteArray(65536)
                    var read = 0L
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        output.write(buf, 0, len)
                        read += len
                        onProgress(read, totalSize)
                    }
                }
            }
            file.close()
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
            val s = share ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD),
            )
            val localFile = File(localPath)
            val totalSize = localFile.length()
            val file = s.openFile(normalizePath(remotePath),
                EnumSet.of(AccessMask.GENERIC_WRITE), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
            FileInputStream(localFile).use { input ->
                file.outputStream.use { output ->
                    val buf = ByteArray(65536)
                    var written = 0L
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        output.write(buf, 0, len)
                        written += len
                        onProgress(written, totalSize)
                    }
                }
            }
            file.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        val s = share ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SIZE)
        try {
            val budget = NetworkTraversalBudget()
            paths.fold(0L) { total, path ->
                currentCoroutineContext().ensureActive()
                checkedNetworkAdd(total, smbSize(s, path, budget))
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
        val s = share ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
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
                for (entry in s.list(normalizePath(path))) {
                    currentCoroutineContext().ensureActive()
                    val name = entry.fileName
                    if (name == "." || name == "..") continue
                    val item = smbEntryToFileItem(entry, path)
                    val matches = if (matcher != null) matcher.containsMatchIn(item.name)
                    else item.name.contains(query, ignoreCase = true)
                    if (matches && (includeHidden || !item.isHidden)) {
                        resultCount++
                        if (resultCount > RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS) {
                            throw IOException("Remote search exceeded ${RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS} results")
                        }
                        emit(item)
                    }
                    if (item.isDirectory) pending.addLast(smbChildPath(path, name) to (depth + 1))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val s = share ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        try {
            val normalized = normalizePath(path)
            val expectedSize = s.getFileInformation(normalized).standardInformation.endOfFile
            if (expectedSize > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
            }
            val digest = checksumDigest(algorithm)
            s.openFile(
                normalized,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { file ->
                file.inputStream.use { input ->
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
                    if (readBytes != expectedSize) throw IOException("SMB checksum source changed during read")
                }
            }
            checksumHex(digest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        }
    }

    private fun smbSize(
        share: DiskShare,
        path: String,
        budget: NetworkTraversalBudget,
        depth: Int = 0,
        visitedDirectories: MutableSet<String> = mutableSetOf(),
    ): Long {
        budget.visit(depth)
        val normalized = normalizePath(path)
        val info = share.getFileInformation(normalized)
        if (!info.standardInformation.isDirectory) return info.standardInformation.endOfFile.coerceAtLeast(0L)
        if (!visitedDirectories.add(normalized)) throw IOException("SMB directory cycle detected: $path")
        try {
            return share.list(normalized)
                .asSequence()
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .fold(0L) { total, entry ->
                    checkedNetworkAdd(
                        total,
                        smbSize(share, smbChildPath(path, entry.fileName), budget, depth + 1, visitedDirectories),
                    )
                }
        } finally {
            visitedDirectories.remove(normalized)
        }
    }

    private fun smbChildPath(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"

    private fun resolveSmbTarget(
        share: DiskShare,
        destination: String,
        sourceName: String,
        resolution: ConflictResolution,
        conflictSuffix: String?,
    ): String? {
        fun exists(name: String): Boolean {
            val path = "${normalizePath(destination)}\\$name"
            return share.fileExists(path) || share.folderExists(path)
        }
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

    private fun smbEntryToFileItem(entry: FileIdBothDirectoryInformation, parentPath: String): FileItem {
        val name = entry.fileName
        val isDir = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        val isHidden = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L
        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        return FileItem(
            name = name, path = fullPath,
            size = entry.endOfFile,
            lastModified = entry.lastWriteTime.toEpochMillis(),
            isDirectory = isDir, isHidden = isHidden || name.startsWith("."),
            mimeType = mime, extension = ext,
        )
    }

    private fun normalizePath(path: String): String {
        return path.replace('/', '\\').trimStart('\\')
    }
}
