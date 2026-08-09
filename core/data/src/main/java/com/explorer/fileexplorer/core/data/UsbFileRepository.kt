package com.explorer.fileexplorer.core.data

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryErrorKind
import com.explorer.fileexplorer.core.model.RepositoryOperation
import com.explorer.fileexplorer.core.model.asRepositoryException
import com.explorer.fileexplorer.core.model.repositoryException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbFileRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbStorageManager: UsbStorageManager,
) : FileRepository {

    override val capabilities: RepositoryCapabilities = RepositoryCapabilities.local("usb")

    override fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val directory = usbStorageManager.documentForPath(path)
        if (directory == null || !directory.isDirectory) {
            emit(emptyList())
            return@flow
        }
        val items = try {
            directory.listFiles().map { document ->
                toFileItem(document, UsbPathCodec.childPath(path, document.name ?: "USB item"))
            }
        } catch (error: Exception) {
            throw error.asRepositoryException(capabilities.provider, RepositoryOperation.LIST)
        }
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        usbStorageManager.documentForPath(path)?.takeIf { it.exists() }?.let { document ->
            toFileItem(document, path)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (UsbPathCodec.isUsbPath(path)) {
            usbStorageManager.documentForPath(path)?.exists() == true
        } else {
            File(path).exists()
        }
    }

    override suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val targetDirectory: Any = if (UsbPathCodec.isUsbPath(destination)) {
            val document = usbStorageManager.documentForPath(destination)
                ?: return@withContext failure(RepositoryOperation.COPY, "USB destination is unavailable", RepositoryErrorKind.NOT_FOUND, false)
            if (!document.isDirectory || !document.canWrite()) {
                return@withContext failure(RepositoryOperation.COPY, "USB destination is not writable", RepositoryErrorKind.PERMISSION, false)
            }
            document
        } else {
            val directory = File(destination)
            if (!directory.isDirectory || !directory.canWrite()) {
                return@withContext failure(RepositoryOperation.COPY, "local destination is not writable", RepositoryErrorKind.PERMISSION, false)
            }
            directory
        }
        val totalBytes = calculateSize(sources)
        var transferred = 0L
        var count = 0
        try {
            for (source in sources) {
                val result = when (targetDirectory) {
                    is DocumentFile -> copySourceToUsb(
                        sourcePath = source,
                        targetDirectory = targetDirectory,
                        resolution = conflictResolution,
                        onProgress = { bytes, name ->
                            transferred += bytes
                            onProgress(transferred, totalBytes, name)
                        },
                    )
                    is File -> copySourceToLocal(
                        sourcePath = source,
                        targetDirectory = targetDirectory,
                        resolution = conflictResolution,
                        onProgress = { bytes, name ->
                            transferred += bytes
                            onProgress(transferred, totalBytes, name)
                        },
                    )
                    else -> throw IllegalStateException("Unsupported transfer destination")
                }
                if (result.success) count++
            }
            Result.success(count)
        } catch (error: Exception) {
            Result.failure(error.asRepositoryException(capabilities.provider, RepositoryOperation.COPY))
        }
    }

    override suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        onProgress: (Long, Long, String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val copyResult = copyFiles(sources, destination, conflictResolution, onProgress)
        if (copyResult.isFailure) return@withContext copyResult
        if (conflictResolution == ConflictResolution.SKIP) return@withContext copyResult
        try {
            sources.forEach { source ->
                val sourceDeleted = if (UsbPathCodec.isUsbPath(source)) {
                    usbStorageManager.documentForPath(source)?.delete() == true
                } else {
                    deleteLocalFile(File(source))
                    true
                }
                if (!sourceDeleted) throw IOException("Unable to remove moved source")
            }
            copyResult
        } catch (error: Exception) {
            Result.failure(error.asRepositoryException(capabilities.provider, RepositoryOperation.MOVE))
        }
    }

    override suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        try {
            for (path in paths) {
                val document = usbStorageManager.documentForPath(path)
                    ?: throw IOException("USB item is unavailable")
                onProgress(document.name ?: "USB item")
                if (!document.delete()) throw IOException("Unable to delete ${document.name ?: "USB item"}")
                count++
            }
            Result.success(count)
        } catch (error: Exception) {
            Result.failure(error.asRepositoryException(capabilities.provider, RepositoryOperation.DELETE))
        }
    }

    override suspend fun createDirectory(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        createChild(path, directory = true)
    }

    override suspend fun createFile(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        createChild(path, directory = false)
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (!UsbNamePolicy.isSafe(newName)) {
            return@withContext failure(RepositoryOperation.RENAME, "invalid USB item name", RepositoryErrorKind.INVALID, false)
        }
        val parentPath = UsbPathCodec.parentPath(path)
            ?: return@withContext failure(RepositoryOperation.RENAME, "USB root cannot be renamed", RepositoryErrorKind.INVALID, false)
        val source = usbStorageManager.documentForPath(path)
            ?: return@withContext failure(RepositoryOperation.RENAME, "USB item is unavailable", RepositoryErrorKind.NOT_FOUND, false)
        val parent = usbStorageManager.documentForPath(parentPath)
            ?: return@withContext failure(RepositoryOperation.RENAME, "USB parent is unavailable", RepositoryErrorKind.NOT_FOUND, false)
        if (parent.findFile(newName) != null) {
            return@withContext failure(RepositoryOperation.RENAME, "an item with that name already exists", RepositoryErrorKind.CONFLICT, false)
        }
        if (!source.renameTo(newName)) {
            return@withContext failure(RepositoryOperation.RENAME, "unable to rename USB item", RepositoryErrorKind.TRANSPORT, true)
        }
        val newPath = UsbPathCodec.childPath(parentPath, newName)
        getFileInfo(newPath)?.let { Result.success(it) }
            ?: failure(RepositoryOperation.RENAME, "USB item was renamed but cannot be read", RepositoryErrorKind.TRANSPORT, true)
    }

    override suspend fun calculateSize(paths: List<String>): Long = withContext(Dispatchers.IO) {
        paths.sumOf { path ->
            if (UsbPathCodec.isUsbPath(path)) {
                usbStorageManager.documentForPath(path)?.let(::documentSize)
                    ?: throw repositoryException(
                        capabilities.provider,
                        RepositoryOperation.SIZE,
                        RepositoryErrorKind.NOT_FOUND,
                        "USB item is unavailable",
                        retryable = false,
                    )
            } else {
                File(path).takeIf { it.exists() }?.let(::localSize)
                    ?: throw repositoryException(
                        capabilities.provider,
                        RepositoryOperation.SIZE,
                        RepositoryErrorKind.NOT_FOUND,
                        "local item is unavailable",
                        retryable = false,
                    )
            }
        }
    }

    override fun search(
        rootPath: String,
        query: String,
        regex: Boolean,
        includeHidden: Boolean,
    ): Flow<FileItem> {
        val root = usbStorageManager.documentForPath(rootPath) ?: return flow {
            throw repositoryException(
                capabilities.provider,
                RepositoryOperation.SEARCH,
                RepositoryErrorKind.NOT_FOUND,
                "USB search root is unavailable",
                retryable = false,
            )
        }
        val matcher = try {
            if (regex) Regex(query, RegexOption.IGNORE_CASE) else null
        } catch (error: Exception) {
            return flow { throw error.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH, RepositoryErrorKind.CORRUPT) }
        }
        if (regex && matcher == null) return flow {
            throw repositoryException(
                capabilities.provider,
                RepositoryOperation.SEARCH,
                RepositoryErrorKind.CORRUPT,
                "invalid search expression",
                retryable = false,
            )
        }
        return flow {
            val pending = ArrayDeque<Pair<DocumentFile, String>>()
            pending.add(root to rootPath)
            while (pending.isNotEmpty()) {
                val (directory, directoryPath) = pending.removeLast()
                val documents = try {
                    directory.listFiles().toList()
                } catch (error: Exception) {
                    throw error.asRepositoryException(capabilities.provider, RepositoryOperation.SEARCH)
                }
                for (document in documents) {
                    val name = document.name ?: continue
                    if (!includeHidden && name.startsWith('.')) continue
                    val item = toFileItem(document, UsbPathCodec.childPath(directoryPath, name))
                    val matches = if (matcher != null) matcher.containsMatchIn(name)
                    else name.contains(query, ignoreCase = true)
                    if (matches) emit(item)
                    if (document.isDirectory) pending.add(document to item.path)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getChecksum(path: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val document = usbStorageManager.documentForPath(path) ?: throw repositoryException(
            capabilities.provider,
            RepositoryOperation.CHECKSUM,
            RepositoryErrorKind.NOT_FOUND,
            "USB item is unavailable",
            retryable = false,
        )
        val digest = try {
            MessageDigest.getInstance(algorithm)
        } catch (error: Exception) {
            throw error.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM, RepositoryErrorKind.INVALID)
        }
        val input = try {
            context.contentResolver.openInputStream(document.uri)
        } catch (error: Exception) {
            throw error.asRepositoryException(capabilities.provider, RepositoryOperation.CHECKSUM)
        } ?: throw repositoryException(
            capabilities.provider,
            RepositoryOperation.CHECKSUM,
            RepositoryErrorKind.STORAGE,
            "unable to open USB item",
            retryable = true,
        )
        input.use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createChild(path: String, directory: Boolean): Result<FileItem> {
        val parentPath = UsbPathCodec.parentPath(path)
            ?: return failure(RepositoryOperation.CREATE_DIRECTORY, "USB root cannot be created", RepositoryErrorKind.INVALID, false)
        val name = UsbPathCodec.name(path)
            ?: return failure(RepositoryOperation.CREATE_DIRECTORY, "USB item name is missing", RepositoryErrorKind.INVALID, false)
        val operation = if (directory) RepositoryOperation.CREATE_DIRECTORY else RepositoryOperation.CREATE_FILE
        if (!UsbNamePolicy.isSafe(name)) return failure(operation, "invalid USB item name", RepositoryErrorKind.INVALID, false)
        val parent = usbStorageManager.documentForPath(parentPath)
            ?: return failure(operation, "USB parent is unavailable", RepositoryErrorKind.NOT_FOUND, false)
        if (parent.findFile(name) != null) return failure(operation, "an item with that name already exists", RepositoryErrorKind.CONFLICT, false)
        val created = if (directory) parent.createDirectory(name) else parent.createFile(mimeType(name), name)
        return created?.let { Result.success(toFileItem(it, path)) }
            ?: failure(operation, "unable to create USB item", RepositoryErrorKind.STORAGE, true)
    }

    private fun <T> failure(
        operation: RepositoryOperation,
        message: String,
        kind: RepositoryErrorKind,
        retryable: Boolean,
    ): Result<T> = Result.failure(repositoryException(capabilities.provider, operation, kind, message, retryable))

    private fun copySourceToUsb(
        sourcePath: String,
        targetDirectory: DocumentFile,
        resolution: ConflictResolution,
        onProgress: (Long, String) -> Unit,
    ): CopyOutcome {
        val sourceDocument = if (UsbPathCodec.isUsbPath(sourcePath)) {
            usbStorageManager.documentForPath(sourcePath)
        } else null
        if (sourceDocument != null) {
            return copyDocument(sourceDocument, targetDirectory, sourceDocument.name ?: "USB item", resolution, onProgress)
        }
        if (UsbPathCodec.isUsbPath(sourcePath)) throw IOException("USB source is unavailable")
        val localSource = File(sourcePath)
        if (!localSource.exists()) throw IOException("Source does not exist: $sourcePath")
        return copyLocal(localSource, targetDirectory, localSource.name, resolution, onProgress)
    }

    private fun copySourceToLocal(
        sourcePath: String,
        targetDirectory: File,
        resolution: ConflictResolution,
        onProgress: (Long, String) -> Unit,
    ): CopyOutcome {
        if (UsbPathCodec.isUsbPath(sourcePath)) {
            val source = usbStorageManager.documentForPath(sourcePath)
                ?: throw IOException("USB source is unavailable")
            return copyDocumentToLocal(source, targetDirectory, source.name ?: "USB item", resolution, onProgress)
        }
        val source = File(sourcePath)
        if (!source.exists()) throw IOException("Source does not exist: $sourcePath")
        return copyLocalToLocal(source, targetDirectory, source.name, resolution, onProgress)
    }

    private fun copyDocument(
        source: DocumentFile,
        targetDirectory: DocumentFile,
        sourceName: String,
        resolution: ConflictResolution,
        onProgress: (Long, String) -> Unit,
    ): CopyOutcome {
        val targetName = prepareTarget(targetDirectory, sourceName, resolution) ?:
            return CopyOutcome(success = true, changed = false, bytes = documentSize(source))
        if (source.isDirectory) {
            val target = targetDirectory.createDirectory(targetName)
                ?: throw IOException("Unable to create USB directory: $targetName")
            var bytes = 0L
            for (child in source.listFiles()) {
                val result = copyDocument(child, target, child.name ?: "USB item", resolution, onProgress)
                bytes += result.bytes
            }
            return CopyOutcome(success = true, changed = true, bytes = bytes)
        }
        val target = targetDirectory.createFile(source.type ?: mimeType(sourceName), targetName)
            ?: throw IOException("Unable to create USB file: $targetName")
        val bytes = context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                copyStream(input, output)
            } ?: throw IOException("Unable to open USB destination: $targetName")
        } ?: throw IOException("Unable to open USB source: $sourceName")
        onProgress(bytes, sourceName)
        return CopyOutcome(success = true, changed = true, bytes = bytes)
    }

    private fun copyLocal(
        source: File,
        targetDirectory: DocumentFile,
        sourceName: String,
        resolution: ConflictResolution,
        onProgress: (Long, String) -> Unit,
    ): CopyOutcome {
        val targetName = prepareTarget(targetDirectory, sourceName, resolution) ?:
            return CopyOutcome(success = true, changed = false, bytes = localSize(source))
        if (source.isDirectory) {
            val target = targetDirectory.createDirectory(targetName)
                ?: throw IOException("Unable to create USB directory: $targetName")
            var bytes = 0L
            source.listFiles()?.forEach { child ->
                val result = copyLocal(child, target, child.name, resolution, onProgress)
                bytes += result.bytes
            }
            return CopyOutcome(success = true, changed = true, bytes = bytes)
        }
        val target = targetDirectory.createFile(mimeType(sourceName), targetName)
            ?: throw IOException("Unable to create USB file: $targetName")
        val bytes = FileInputStream(source).use { input ->
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                copyStream(input, output)
            } ?: throw IOException("Unable to open USB destination: $targetName")
        }
        onProgress(bytes, sourceName)
        return CopyOutcome(success = true, changed = true, bytes = bytes)
    }

    private fun copyDocumentToLocal(
        source: DocumentFile,
        targetDirectory: File,
        sourceName: String,
        resolution: ConflictResolution,
        onProgress: (Long, String) -> Unit,
    ): CopyOutcome {
        val targetName = prepareLocalTarget(targetDirectory, sourceName, resolution)
            ?: return CopyOutcome(success = true, changed = false, bytes = documentSize(source))
        val target = File(targetDirectory, targetName)
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("Unable to create local directory: $target")
            var bytes = 0L
            for (child in source.listFiles()) {
                bytes += copyDocumentToLocal(child, target, child.name ?: "USB item", resolution, onProgress).bytes
            }
            return CopyOutcome(success = true, changed = true, bytes = bytes)
        }
        val bytes = context.contentResolver.openInputStream(source.uri)?.use { input ->
            FileOutputStream(target).use { output -> copyStream(input, output) }
        } ?: throw IOException("Unable to open USB source: $sourceName")
        onProgress(bytes, sourceName)
        return CopyOutcome(success = true, changed = true, bytes = bytes)
    }

    private fun copyLocalToLocal(
        source: File,
        targetDirectory: File,
        sourceName: String,
        resolution: ConflictResolution,
        onProgress: (Long, String) -> Unit,
    ): CopyOutcome {
        val targetName = prepareLocalTarget(targetDirectory, sourceName, resolution)
            ?: return CopyOutcome(success = true, changed = false, bytes = localSize(source))
        val target = File(targetDirectory, targetName)
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("Unable to create local directory: $target")
            var bytes = 0L
            source.listFiles()?.forEach { child ->
                bytes += copyLocalToLocal(child, target, child.name, resolution, onProgress).bytes
            }
            return CopyOutcome(success = true, changed = true, bytes = bytes)
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> copyStream(input, output) }
        }
        val bytes = source.length()
        onProgress(bytes, sourceName)
        return CopyOutcome(success = true, changed = true, bytes = bytes)
    }

    private fun prepareTarget(
        parent: DocumentFile,
        sourceName: String,
        resolution: ConflictResolution,
    ): String? {
        val existing = parent.findFile(sourceName)
        if (existing == null) return sourceName
        return when (resolution) {
            ConflictResolution.OVERWRITE -> {
                if (!existing.delete()) throw IOException("Unable to replace $sourceName")
                sourceName
            }

            ConflictResolution.SKIP -> null
            ConflictResolution.RENAME, ConflictResolution.ASK -> uniqueName(parent, sourceName)
        }
    }

    private fun prepareLocalTarget(
        parent: File,
        sourceName: String,
        resolution: ConflictResolution,
    ): String? {
        val existing = File(parent, sourceName)
        if (!existing.exists()) return sourceName
        return when (resolution) {
            ConflictResolution.OVERWRITE -> {
                deleteLocalFile(existing)
                sourceName
            }
            ConflictResolution.SKIP -> null
            ConflictResolution.RENAME, ConflictResolution.ASK -> uniqueLocalName(parent, sourceName)
        }
    }

    private fun uniqueLocalName(parent: File, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        var candidate: String
        do {
            candidate = "$base ($counter)$extension"
            counter++
        } while (File(parent, candidate).exists())
        return candidate
    }

    private fun uniqueName(parent: DocumentFile, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        var candidate: String
        do {
            candidate = "$base ($counter)$extension"
            counter++
        } while (parent.findFile(candidate) != null)
        return candidate
    }

    private fun toFileItem(document: DocumentFile, exactPath: String): FileItem {
        val name = document.name ?: "USB item"
        val extension = name.substringAfterLast('.', "")
        val isDirectory = document.isDirectory
        return FileItem(
            name = name,
            path = exactPath,
            uri = document.uri,
            size = if (isDirectory) 0L else document.length(),
            lastModified = document.lastModified(),
            isDirectory = isDirectory,
            isHidden = name.startsWith('.'),
            isReadable = document.canRead(),
            isWritable = document.canWrite(),
            mimeType = if (isDirectory) "inode/directory" else document.type ?: mimeType(name),
            extension = extension,
            permissions = emptySet<PosixFilePermission>(),
        )
    }

    private fun copyStream(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun documentSize(document: DocumentFile): Long {
        if (!document.isDirectory) return document.length()
        return document.listFiles().sumOf(::documentSize)
    }

    private fun localSize(file: File): Long {
        if (!file.isDirectory) return file.length()
        return file.listFiles()?.sumOf(::localSize) ?: 0L
    }

    private fun deleteLocalFile(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteLocalFile)
        if (!file.delete()) throw IOException("Unable to delete ${file.path}")
    }

    private fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private data class CopyOutcome(
        val success: Boolean,
        val changed: Boolean,
        val bytes: Long,
    )
}

object UsbNamePolicy {
    fun isSafe(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        !name.contains('/') && !name.contains('\\') && name.none { it.isISOControl() }
}
