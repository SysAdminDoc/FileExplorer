package com.explorer.fileexplorer.core.network.smb

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.network.NetworkConnection
import com.explorer.fileexplorer.core.network.NetworkFileRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SmbFileRepository @Inject constructor() : NetworkFileRepository {

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
            Result.failure(e)
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
        val s = share ?: run { emit(emptyList()); return@flow }
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
            // Return whatever we have
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val s = share ?: return@withContext null
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
        } catch (_: Exception) { null }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        share?.let { s ->
            try { s.fileExists(normalizePath(path)) || s.folderExists(normalizePath(path)) }
            catch (_: Exception) { false }
        } ?: false
    }

    override suspend fun copyFiles(
        sources: List<String>, destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        // SMB copy: read from source, write to destination on same share
        var count = 0
        try {
            val s = share ?: return@withContext Result.failure(Exception("Not connected"))
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destPath = "${normalizePath(destination)}\\$name"
                onProgress(0, 0, name)

                val srcFile = s.openFile(normalizePath(src),
                    EnumSet.of(AccessMask.GENERIC_READ), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN, null)

                val dstFile = s.openFile(destPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null)

                srcFile.inputStream.use { input ->
                    dstFile.outputStream.use { output ->
                        val buf = ByteArray(65536)
                        var totalRead = 0L
                        var len: Int
                        while (input.read(buf).also { len = it } != -1) {
                            output.write(buf, 0, len)
                            totalRead += len
                            onProgress(totalRead, 0, name)
                        }
                    }
                }
                srcFile.close(); dstFile.close()
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
        val s = share ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (src in sources) {
                val name = src.trimEnd('/').substringAfterLast('/')
                val destPath = "${normalizePath(destination)}\\$name"
                onProgress(0, 0, name)
                s.openFile(normalizePath(src),
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN, null).use { f ->
                    f.rename(destPath)
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val s = share ?: return@withContext Result.failure(Exception("Not connected"))
        var count = 0
        try {
            for (path in paths) {
                val p = normalizePath(path)
                onProgress(path.substringAfterLast('/'))
                if (s.folderExists(p)) s.rmdir(p, true) else s.rm(p)
                count++
            }
            Result.success(count)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            share?.mkdir(normalizePath(path))
            getFileInfo(path)?.let { Result.success(it) }
                ?: Result.failure(Exception("Created but stat failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val s = share ?: return@withContext Result.failure(Exception("Not connected"))
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
                ?: Result.failure(Exception("Renamed but stat failed"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun download(
        remotePath: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = share ?: return@withContext Result.failure(Exception("Not connected"))
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
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun upload(
        localPath: String, remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val s = share ?: return@withContext Result.failure(Exception("Not connected"))
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
        } catch (e: Exception) { Result.failure(e) }
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
