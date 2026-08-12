package com.explorer.fileexplorer.core.network.sftp

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.ConflictNamePolicy
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

class SftpFileRepository @Inject constructor(
    private val knownHostsStore: SftpKnownHostsStore,
    private val diagnosticLog: com.explorer.fileexplorer.core.data.DiagnosticLog,
) : NetworkFileRepository {

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.network(
        provider = "sftp",
        advancedOperations = true,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 60_000
    }

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var currentConnection: NetworkConnection? = null

    override val isConnected: Boolean get() = ssh?.isConnected == true && sftp != null

    override suspend fun connect(connection: NetworkConnection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val client = SSHClient()
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.timeout = SOCKET_TIMEOUT_MS
            client.addHostKeyVerifier(knownHostsStore.createVerifier())
            client.connect(connection.host, connection.port)

            // Auth: key first, then password
            if (connection.privateKeyPath.isNotEmpty()) {
                val keyFile = File(connection.privateKeyPath)
                val keyProvider: KeyProvider = if (connection.password.isNotEmpty()) {
                    client.loadKeys(keyFile.absolutePath, connection.password)
                } else {
                    client.loadKeys(keyFile.absolutePath)
                }
                client.authPublickey(connection.username, keyProvider)
            } else if (connection.password.isNotEmpty()) {
                client.authPassword(connection.username, connection.password)
            } else {
                client.authPassword(connection.username, "")
            }

            ssh = client
            sftp = client.newSFTPClient()
            currentConnection = connection
            Result.success(Unit)
        } catch (e: Exception) {
            val typedError = e.asRepositoryException("sftp", RepositoryOperation.CONNECT)
            diagnosticLog.log(typedError.error, "${connection.host}:${connection.port}")
            disconnect()
            Result.failure(typedError)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try { sftp?.close() } catch (_: Exception) {}
        try { ssh?.disconnect() } catch (_: Exception) {}
        sftp = null; ssh = null; currentConnection = null
    }

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val s = sftp ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        val items = mutableListOf<FileItem>()
        try {
            val entries = s.ls(path)
            for (entry in entries) {
                val name = entry.name
                if (name == "." || name == "..") continue
                items.add(remoteInfoToFileItem(entry, path))
            }
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val s = sftp ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        try {
            val attrs = s.stat(path)
            val name = path.trimEnd('/').substringAfterLast('/')
            attrsToFileItem(name, path, attrs)
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource() || (e is SFTPException && e.statusCode == Response.StatusCode.NO_SUCH_FILE)) null
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.INFO)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val s = sftp ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        try {
            s.stat(path)
            true
        } catch (e: Exception) {
            if (e.isMissingRepositoryResource() || (e is SFTPException && e.statusCode == Response.StatusCode.NO_SUCH_FILE)) false
            else throw e.asRepositoryException(capabilities.provider, RepositoryOperation.EXISTS)
        }
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.COPY),
        )
        val tempRoot = try {
            Files.createTempDirectory("fileexplorer-sftp-copy-").toFile()
        } catch (e: Exception) {
            return@withContext Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.COPY))
        }
        var transferred = 0L
        try {
            ensureRemoteDirectory(s, destination)
            val totalSize = sources.fold(0L) { total, source ->
                checkedNetworkAdd(total, remoteSize(s, source))
            }
            var count = 0
            for (src in sources) {
                coroutineContext.ensureActive()
                val name = SftpRemotePath.fileName(src)
                val destPath = SftpRemotePath.child(destination, name)
                if (SftpRemotePath.isSameOrDescendant(src, destPath)) {
                    throw IOException("Refusing to copy a path into itself or a descendant: $src")
                }
                copyRemoteTree(
                    sftp = s,
                    source = src,
                    target = destPath,
                    conflictResolution = conflictResolution,
                    conflictSuffix = conflictSuffix,
                    localTempRoot = tempRoot,
                    onBytesTransferred = { bytes, itemName ->
                        transferred += bytes
                        onProgress(transferred, totalSize, itemName)
                    },
                )
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val typedError = e.asRepositoryException(capabilities.provider, RepositoryOperation.COPY)
            diagnosticLog.log(typedError.error, destination)
            Result.failure(typedError)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    override suspend fun moveFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.MOVE),
        )
        try {
            ensureRemoteDirectory(s, destination)
            val totalSize = sources.fold(0L) { total, source ->
                checkedNetworkAdd(total, remoteSize(s, source))
            }
            var transferred = 0L
            var count = 0
            for (src in sources) {
                coroutineContext.ensureActive()
                val name = SftpRemotePath.fileName(src)
                val requestedTarget = SftpRemotePath.child(destination, name)
                if (SftpRemotePath.isSameOrDescendant(src, requestedTarget)) {
                    throw IOException("Refusing to move a path into itself or a descendant: $src")
                }
                val sourceAttributes = s.lstat(src)
                rejectUnsupportedSource(src, sourceAttributes)
                val sourceSize = remoteSize(s, src)
                val target = resolveConflictTarget(s, requestedTarget, sourceAttributes.type, conflictResolution, conflictSuffix)
                if (target == null) {
                    transferred += sourceSize
                    onProgress(transferred, totalSize, name)
                    count++
                    continue
                }
                coroutineContext.ensureActive()
                s.rename(
                    src,
                    target,
                    if (conflictResolution == ConflictResolution.OVERWRITE) {
                        setOf(RenameFlags.OVERWRITE)
                    } else {
                        emptySet()
                    },
                )
                verifyRemoteType(s, target, sourceAttributes.type)
                transferred += sourceSize
                onProgress(transferred, totalSize, name)
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val typedError = e.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE)
            diagnosticLog.log(typedError.error, destination)
            Result.failure(typedError)
        }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(
            notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DELETE),
        )
        var count = 0
        try {
            for (path in paths) {
                onProgress(path.substringAfterLast('/'))
                val attrs = s.stat(path)
                if (attrs.type == FileMode.Type.DIRECTORY) {
                    deleteRecursive(s, path)
                } else {
                    s.rm(path)
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
    }

    private fun ensureRemoteDirectory(sftp: SFTPClient, path: String) {
        val attributes = lstatIfExists(sftp, path)
        when {
            attributes == null -> {
                sftp.mkdirs(path)
                verifyRemoteType(sftp, path, FileMode.Type.DIRECTORY)
            }
            attributes.type == FileMode.Type.SYMLINK ->
                throw IOException("Refusing to use a symbolic-link destination: $path")
            attributes.type != FileMode.Type.DIRECTORY ->
                throw IOException("SFTP destination is not a directory: $path")
        }
    }

    private suspend fun remoteSize(
        sftp: SFTPClient,
        path: String,
        visitedDirectories: MutableSet<String> = mutableSetOf(),
        budget: NetworkTraversalBudget = NetworkTraversalBudget(),
        depth: Int = 0,
    ): Long {
        coroutineContext.ensureActive()
        budget.visit(depth)
        val attributes = sftp.lstat(path)
        rejectUnsupportedSource(path, attributes)
        return when (attributes.type) {
            FileMode.Type.REGULAR -> attributes.size.coerceAtLeast(0L)
            FileMode.Type.DIRECTORY -> {
                if (!visitedDirectories.add(path)) {
                    throw IOException("Remote directory cycle detected: $path")
                }
                try {
                    sftp.ls(path)
                        .asSequence()
                        .filterNot { it.name == "." || it.name == ".." }
                        .fold(0L) { total, entry ->
                            checkedNetworkAdd(
                                total,
                                remoteSize(
                                    sftp = sftp,
                                    path = SftpRemotePath.child(path, entry.name),
                                    visitedDirectories = visitedDirectories,
                                    budget = budget,
                                    depth = depth + 1,
                                ),
                            )
                        }
                } finally {
                    visitedDirectories.remove(path)
                }
            }
            else -> throw IOException("Unsupported remote entry type at $path: ${attributes.type}")
        }
    }

    private fun addSizes(first: Long, second: Long): Long = checkedNetworkAdd(first, second)

    private fun rejectUnsupportedSource(path: String, attributes: FileAttributes) {
        when (attributes.type) {
            FileMode.Type.REGULAR, FileMode.Type.DIRECTORY -> Unit
            FileMode.Type.SYMLINK -> throw IOException("Refusing to copy a symbolic link: $path")
            else -> throw IOException("Unsupported remote entry type at $path: ${attributes.type}")
        }
    }

    private fun resolveConflictTarget(
        sftp: SFTPClient,
        requestedTarget: String,
        sourceType: FileMode.Type,
        conflictResolution: ConflictResolution,
        conflictSuffix: String? = null,
    ): String? {
        val existing = lstatIfExists(sftp, requestedTarget) ?: return requestedTarget
        return when (conflictResolution) {
            ConflictResolution.SKIP -> null
            ConflictResolution.RENAME -> conflictSuffix?.let {
                val deterministic = SftpRemotePath.child(
                    SftpRemotePath.parent(requestedTarget),
                    ConflictNamePolicy.fileName(SftpRemotePath.fileName(requestedTarget), it),
                )
                if (lstatIfExists(sftp, deterministic) == null) deterministic
                else uniqueConflictTarget(sftp, deterministic)
            } ?: uniqueConflictTarget(sftp, requestedTarget)
            ConflictResolution.ASK -> throw IOException("SFTP target already exists: $requestedTarget")
            ConflictResolution.OVERWRITE -> {
                if (existing.type == FileMode.Type.SYMLINK) {
                    throw IOException("Refusing to overwrite a symbolic link: $requestedTarget")
                }
                if (existing.type != sourceType) {
                    throw IOException("SFTP target type conflicts with source: $requestedTarget")
                }
                requestedTarget
            }
        }
    }

    private fun uniqueConflictTarget(sftp: SFTPClient, requestedTarget: String): String {
        val parent = SftpRemotePath.parent(requestedTarget)
        val name = SftpRemotePath.fileName(requestedTarget)
        for (index in 1..10_000) {
            val candidate = SftpRemotePath.child(parent, "$name ($index)")
            if (lstatIfExists(sftp, candidate) == null) return candidate
        }
        throw IOException("Unable to find an unused SFTP target name for: $requestedTarget")
    }

    private suspend fun copyRemoteTree(
        sftp: SFTPClient,
        source: String,
        target: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String?,
        localTempRoot: File,
        onBytesTransferred: (Long, String) -> Unit,
    ): Long {
        coroutineContext.ensureActive()
        val sourceAttributes = sftp.lstat(source)
        rejectUnsupportedSource(source, sourceAttributes)
        val resolvedTarget = resolveConflictTarget(sftp, target, sourceAttributes.type, conflictResolution, conflictSuffix)
        if (resolvedTarget == null) {
            val skippedSize = remoteSize(sftp, source)
            onBytesTransferred(skippedSize, SftpRemotePath.fileName(source))
            return skippedSize
        }

        return when (sourceAttributes.type) {
            FileMode.Type.REGULAR -> copyRemoteFile(
                sftp = sftp,
                source = source,
                target = resolvedTarget,
                expectedSize = sourceAttributes.size.coerceAtLeast(0L),
                localTempRoot = localTempRoot,
                onBytesTransferred = onBytesTransferred,
            )
            FileMode.Type.DIRECTORY -> copyRemoteDirectory(
                sftp = sftp,
                source = source,
                target = resolvedTarget,
                localTempRoot = localTempRoot,
                onBytesTransferred = onBytesTransferred,
            )
            else -> error("Unsupported source type was not rejected")
        }
    }

    private suspend fun copyRemoteFile(
        sftp: SFTPClient,
        source: String,
        target: String,
        expectedSize: Long,
        localTempRoot: File,
        onBytesTransferred: (Long, String) -> Unit,
    ): Long {
        val localStagingFile = File(localTempRoot, "${UUID.randomUUID()}.part")
        val remoteStagingFile = temporaryRemoteSibling(sftp, SftpRemotePath.parent(target))
        try {
            coroutineContext.ensureActive()
            sftp.get(source, FileSystemFile(localStagingFile))
            coroutineContext.ensureActive()
            if (!localStagingFile.isFile || localStagingFile.length() != expectedSize) {
                throw IOException("SFTP download size mismatch for $source")
            }

            sftp.put(FileSystemFile(localStagingFile), remoteStagingFile)
            verifyRemoteFile(sftp, remoteStagingFile, expectedSize)
            coroutineContext.ensureActive()
            sftp.rename(remoteStagingFile, target, setOf(RenameFlags.OVERWRITE))
            verifyRemoteFile(sftp, target, expectedSize)
            onBytesTransferred(expectedSize, SftpRemotePath.fileName(source))
            return expectedSize
        } finally {
            cleanupRemotePath(sftp, remoteStagingFile)
            localStagingFile.delete()
        }
    }

    private suspend fun copyRemoteDirectory(
        sftp: SFTPClient,
        source: String,
        target: String,
        localTempRoot: File,
        onBytesTransferred: (Long, String) -> Unit,
    ): Long {
        val remoteStagingDirectory = temporaryRemoteSibling(sftp, SftpRemotePath.parent(target))
        var copiedBytes = 0L
        try {
            sftp.mkdir(remoteStagingDirectory)
            for (entry in sftp.ls(source)) {
                coroutineContext.ensureActive()
                if (entry.name == "." || entry.name == "..") continue
                copiedBytes = addSizes(
                    copiedBytes,
                    copyRemoteTree(
                        sftp = sftp,
                        source = SftpRemotePath.child(source, entry.name),
                        target = SftpRemotePath.child(remoteStagingDirectory, entry.name),
                        conflictResolution = ConflictResolution.OVERWRITE,
                        conflictSuffix = null,
                        localTempRoot = localTempRoot,
                        onBytesTransferred = onBytesTransferred,
                    ),
                )
            }
            commitRemoteDirectory(sftp, remoteStagingDirectory, target)
            return copiedBytes
        } finally {
            cleanupRemotePath(sftp, remoteStagingDirectory)
        }
    }

    private fun commitRemoteDirectory(sftp: SFTPClient, staging: String, target: String) {
        val existing = lstatIfExists(sftp, target)
        when {
            existing == null -> {
                sftp.rename(staging, target)
                verifyRemoteType(sftp, target, FileMode.Type.DIRECTORY)
            }
            existing.type == FileMode.Type.SYMLINK ->
                throw IOException("Refusing to overwrite a symbolic-link directory: $target")
            existing.type != FileMode.Type.DIRECTORY ->
                throw IOException("SFTP directory target is not a directory: $target")
            else -> mergeRemoteDirectory(sftp, staging, target)
        }
    }

    private fun mergeRemoteDirectory(sftp: SFTPClient, staging: String, target: String) {
        for (entry in sftp.ls(staging)) {
            if (entry.name == "." || entry.name == "..") continue
            val stagedChild = SftpRemotePath.child(staging, entry.name)
            val targetChild = SftpRemotePath.child(target, entry.name)
            val stagedAttributes = sftp.lstat(stagedChild)
            rejectUnsupportedSource(stagedChild, stagedAttributes)
            val targetAttributes = lstatIfExists(sftp, targetChild)
            if (targetAttributes == null) {
                sftp.rename(stagedChild, targetChild)
                verifyRemoteType(sftp, targetChild, stagedAttributes.type)
                continue
            }
            if (targetAttributes.type == FileMode.Type.SYMLINK) {
                throw IOException("Refusing to overwrite a symbolic-link target: $targetChild")
            }
            if (stagedAttributes.type == FileMode.Type.DIRECTORY && targetAttributes.type == FileMode.Type.DIRECTORY) {
                mergeRemoteDirectory(sftp, stagedChild, targetChild)
                continue
            }
            if (stagedAttributes.type != targetAttributes.type) {
                throw IOException("SFTP directory entry type conflicts with target: $targetChild")
            }
            sftp.rename(stagedChild, targetChild, setOf(RenameFlags.OVERWRITE))
            if (stagedAttributes.type == FileMode.Type.REGULAR) {
                verifyRemoteFile(sftp, targetChild, stagedAttributes.size.coerceAtLeast(0L))
            }
        }
        sftp.rmdir(staging)
    }

    private fun temporaryRemoteSibling(sftp: SFTPClient, parent: String): String {
        repeat(10) {
            val candidate = SftpRemotePath.child(parent, ".fileexplorer-sftp-${UUID.randomUUID()}.part")
            if (lstatIfExists(sftp, candidate) == null) return candidate
        }
        throw IOException("Unable to allocate a temporary SFTP target")
    }

    private fun verifyRemoteFile(sftp: SFTPClient, path: String, expectedSize: Long) {
        val attributes = lstatIfExists(sftp, path)
            ?: throw IOException("SFTP transfer did not produce a destination: $path")
        if (attributes.type != FileMode.Type.REGULAR || attributes.size != expectedSize) {
            throw IOException("SFTP transfer verification failed for $path")
        }
    }

    private fun verifyRemoteType(sftp: SFTPClient, path: String, expectedType: FileMode.Type) {
        val attributes = lstatIfExists(sftp, path)
            ?: throw IOException("SFTP operation did not produce a destination: $path")
        if (attributes.type != expectedType) {
            throw IOException("SFTP operation verification failed for $path")
        }
    }

    private fun lstatIfExists(sftp: SFTPClient, path: String): FileAttributes? {
        return try {
            sftp.lstat(path)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
        }
    }

    private fun cleanupRemotePath(sftp: SFTPClient, path: String) {
        try {
            val attributes = lstatIfExists(sftp, path) ?: return
            if (attributes.type == FileMode.Type.DIRECTORY) {
                for (entry in sftp.ls(path)) {
                    if (entry.name != "." && entry.name != "..") {
                        cleanupRemotePath(sftp, SftpRemotePath.child(path, entry.name))
                    }
                }
                sftp.rmdir(path)
            } else {
                sftp.rm(path)
            }
        } catch (e: Exception) {
            diagnosticLog.log("SFTP", "cleanup", e, path)
        }
    }

    private fun deleteRecursive(s: SFTPClient, path: String) {
        val entries = s.ls(path)
        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val fullPath = "$path/${entry.name}"
            if (entry.isDirectory) deleteRecursive(s, fullPath) else s.rm(fullPath)
        }
        s.rmdir(path)
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val s = sftp ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CREATE_DIRECTORY),
            )
            s.mkdir(path)
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
            val s = sftp ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.RENAME),
            )
            val parent = path.trimEnd('/').substringBeforeLast('/')
            val target = "$parent/$newName"
            s.rename(path, target)
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
            val s = sftp ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.DOWNLOAD),
            )
            val totalSize = s.stat(remotePath).size
            onProgress(0, totalSize)
            s.get(remotePath, FileSystemFile(localPath))
            if (File(localPath).length() != totalSize) throw IOException("SFTP download size mismatch")
            onProgress(totalSize, totalSize)
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
            val s = sftp ?: return@withContext Result.failure(
                notConnectedRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD),
            )
            val localFile = File(localPath)
            onProgress(0, localFile.length())
            s.put(FileSystemFile(localFile), remotePath)
            onProgress(localFile.length(), localFile.length())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(capabilities.provider, RepositoryOperation.UPLOAD))
        }
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        val s = sftp ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SIZE)
        try {
            val budget = NetworkTraversalBudget()
            paths.fold(0L) { total, path ->
                coroutineContext.ensureActive()
                checkedNetworkAdd(total, remoteSize(s, path, budget = budget))
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
        val s = sftp ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
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
                val attributes = s.lstat(path)
                if (attributes.type != FileMode.Type.DIRECTORY) continue
                for (entry in s.ls(path)) {
                    currentCoroutineContext().ensureActive()
                    if (entry.name == "." || entry.name == ".." || entry.attributes.type == FileMode.Type.SYMLINK) continue
                    val item = remoteInfoToFileItem(entry, path)
                    val matches = if (matcher != null) matcher.containsMatchIn(item.name)
                    else item.name.contains(query, ignoreCase = true)
                    if (matches && (includeHidden || !item.isHidden)) {
                        resultCount++
                        if (resultCount > RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS) {
                            throw IOException("Remote search exceeded ${RepositoryOperationLimits.MAX_NETWORK_SEARCH_RESULTS} results")
                        }
                        emit(item)
                    }
                    if (entry.attributes.type == FileMode.Type.DIRECTORY) {
                        pending.addLast(SftpRemotePath.child(path, entry.name) to (depth + 1))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val s = sftp ?: throw notConnectedRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        val staging = try {
            Files.createTempFile("fileexplorer-sftp-checksum-", ".part").toFile()
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        }
        try {
            val expectedSize = s.stat(path).size.coerceAtLeast(0L)
            if (expectedSize > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
            }
            coroutineContext.ensureActive()
            s.get(path, FileSystemFile(staging))
            val digest = checksumDigest(algorithm)
            var readBytes = 0L
            FileInputStream(staging).use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()
                    readBytes += read
                    if (readBytes > RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES) {
                        throw IOException("Remote checksum exceeds ${RepositoryOperationLimits.MAX_NETWORK_CHECKSUM_BYTES} bytes")
                    }
                    digest.update(buffer, 0, read)
                }
            }
            if (readBytes != expectedSize) throw IOException("SFTP checksum source changed during download")
            checksumHex(digest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        } finally {
            staging.delete()
        }
    }

    /** Execute a command over SSH. */
    suspend fun execCommand(command: String): String = withContext(Dispatchers.IO) {
        val session = ssh?.startSession() ?: return@withContext ""
        try {
            val cmd = session.exec(command)
            cmd.inputStream.bufferedReader().readText()
        } finally {
            session.close()
        }
    }

    private fun remoteInfoToFileItem(info: RemoteResourceInfo, parentPath: String): FileItem {
        val name = info.name
        val attrs = info.attributes
        return attrsToFileItem(name, "$parentPath/$name", attrs)
    }

    private fun attrsToFileItem(name: String, path: String, attrs: FileAttributes): FileItem {
        val isDir = attrs.type == FileMode.Type.DIRECTORY
        val isLink = attrs.type == FileMode.Type.SYMLINK
        val ext = name.substringAfterLast('.', "")
        val mime = if (isDir) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        val permissions = parseSftpPermissions(attrs.permissions)
        return FileItem(
            name = name, path = path,
            size = attrs.size, lastModified = attrs.mtime * 1000L,
            isDirectory = isDir, isHidden = name.startsWith("."),
            isSymlink = isLink, mimeType = mime, extension = ext,
            permissions = permissions,
            ownerName = attrs.uid.toString(),
            groupName = attrs.gid.toString(),
        )
    }

    private fun parseSftpPermissions(perms: Set<*>): Set<PosixFilePermission> {
        // Map sshj permission enum names to PosixFilePermission
        val result = mutableSetOf<PosixFilePermission>()
        for (p in perms) {
            when (p.toString()) {
                "USR_R" -> result.add(PosixFilePermission.OWNER_READ)
                "USR_W" -> result.add(PosixFilePermission.OWNER_WRITE)
                "USR_X" -> result.add(PosixFilePermission.OWNER_EXECUTE)
                "GRP_R" -> result.add(PosixFilePermission.GROUP_READ)
                "GRP_W" -> result.add(PosixFilePermission.GROUP_WRITE)
                "GRP_X" -> result.add(PosixFilePermission.GROUP_EXECUTE)
                "OTH_R" -> result.add(PosixFilePermission.OTHERS_READ)
                "OTH_W" -> result.add(PosixFilePermission.OTHERS_WRITE)
                "OTH_X" -> result.add(PosixFilePermission.OTHERS_EXECUTE)
            }
        }
        return result
    }
}

internal object SftpRemotePath {
    fun fileName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            throw IllegalArgumentException("Remote path has no file name: $path")
        }
        return trimmed.substringAfterLast('/').also { name ->
            require(name.isNotEmpty() && name != "." && name != "..") {
                "Remote path has no file name: $path"
            }
        }
    }

    fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val separator = trimmed.lastIndexOf('/')
        return when {
            separator < 0 -> "."
            separator == 0 -> "/"
            else -> trimmed.substring(0, separator)
        }
    }

    fun child(parent: String, name: String): String = when {
        parent == "/" -> "/$name"
        parent.isEmpty() || parent == "." -> name
        else -> "${parent.trimEnd('/')}/$name"
    }

    fun isSameOrDescendant(source: String, target: String): Boolean {
        val normalizedSource = source.trimEnd('/').ifEmpty { "/" }
        val normalizedTarget = target.trimEnd('/').ifEmpty { "/" }
        return normalizedTarget == normalizedSource ||
            (normalizedSource != "/" && normalizedTarget.startsWith("$normalizedSource/"))
    }
}
