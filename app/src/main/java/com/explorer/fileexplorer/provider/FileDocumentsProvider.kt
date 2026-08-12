package com.explorer.fileexplorer.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Exposes the user-visible local storage root through Android's Storage Access Framework. */
class FileDocumentsProvider @JvmOverloads internal constructor(
    private val rootOverride: File? = null,
) : DocumentsProvider() {

    override fun onCreate(): Boolean = appContext() != null

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val localStorageLabel = appContext()?.getString(DesignSystemR.string.local_storage) ?: return cursor
        roots().forEach { root ->
            val row = cursor.newRow()
            cursor.columnNames.forEach { column ->
                when (column) {
                    Root.COLUMN_ROOT_ID -> row.add(column, root.id)
                    Root.COLUMN_DOCUMENT_ID -> row.add(column, root.id)
                    Root.COLUMN_TITLE -> row.add(column, root.title)
                    Root.COLUMN_SUMMARY -> row.add(column, localStorageLabel)
                    Root.COLUMN_FLAGS -> row.add(column, rootFlags(root.file))
                    Root.COLUMN_ICON -> row.add(column, android.R.drawable.ic_menu_save)
                    Root.COLUMN_MIME_TYPES -> row.add(column, "*/*")
                    Root.COLUMN_AVAILABLE_BYTES -> row.add(column, root.file.usableSpace)
                    Root.COLUMN_CAPACITY_BYTES -> row.add(column, root.file.totalSpace)
                }
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val file = documentFile(documentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        addDocumentRow(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = documentFile(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")

        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        parent.listFiles()
            ?.asSequence()
            ?.filterNot { Files.isSymbolicLink(it.toPath()) }
            ?.sortedWith { left, right ->
                left.name.lowercase(Locale.ROOT).compareTo(right.name.lowercase(Locale.ROOT))
            }
            ?.forEach { addDocumentRow(cursor, it) }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<String>?): Cursor {
        val root = rootForId(rootId).file
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val recent = mutableListOf<File>()
        walkFiles(root) { file ->
            if (file != root) recent += file
        }
        recent.sortByDescending { it.lastModified() }
        recent.take(MAX_RECENT_DOCUMENTS).forEach { addDocumentRow(cursor, it) }
        return cursor
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?,
    ): Cursor {
        val root = rootForId(rootId).file
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        var count = 0
        walkFiles(root) { file ->
            if (count < MAX_SEARCH_RESULTS &&
                file != root &&
                file.name.lowercase(Locale.ROOT).contains(normalizedQuery)
            ) {
                addDocumentRow(cursor, file)
                count++
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (signal?.isCanceled == true) throw IOException("Open cancelled")
        val file = documentFile(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file: $documentId")
        if (mode != "r" && !file.canWrite()) throw FileNotFoundException("Document is read-only: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = documentFile(parentDocumentId)
        ensureWritableDirectory(parent)
        val target = safeChild(parent, displayName)
        if (target.exists()) throw FileNotFoundException("Document already exists: $displayName")
        try {
            if (mimeType == Document.MIME_TYPE_DIR) {
                Files.createDirectory(target.toPath())
            } else {
                Files.createFile(target.toPath())
            }
        } catch (error: IOException) {
            throw notFound("Unable to create document: $displayName", error)
        }
        val documentId = documentId(target)
        notifyDocumentsChanged(documentId, parentDocumentId)
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        val file = documentFile(documentId)
        if (isRoot(file)) throw FileNotFoundException("Storage roots cannot be deleted")
        val parentId = file.parentFile?.let(::documentId)
        try {
            deleteRecursively(file.toPath())
        } catch (error: IOException) {
            throw notFound("Unable to delete document: $documentId", error)
        }
        notifyDocumentsChanged(documentId, parentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = documentFile(documentId)
        if (isRoot(source)) throw FileNotFoundException("Storage roots cannot be renamed")
        val parent = source.parentFile ?: throw FileNotFoundException("Document has no parent")
        ensureWritableDirectory(parent)
        val target = safeChild(parent, displayName)
        if (target.exists()) throw FileNotFoundException("Document already exists: $displayName")
        try {
            Files.move(source.toPath(), target.toPath())
        } catch (error: IOException) {
            throw notFound("Unable to rename document: $documentId", error)
        }
        val renamedId = documentId(target)
        notifyDocumentsChanged(documentId, documentId(parent))
        notifyDocumentsChanged(renamedId, documentId(parent))
        return renamedId
    }

    override fun copyDocument(documentId: String, targetParentDocumentId: String): String {
        val source = documentFile(documentId)
        val parent = documentFile(targetParentDocumentId)
        if (isRoot(source) || DocumentPathPolicy.isChild(source.toPath(), parent.toPath())) {
            throw FileNotFoundException("A document cannot be copied into itself")
        }
        ensureWritableDirectory(parent)
        val target = safeChild(parent, source.name)
        if (target.exists()) throw FileNotFoundException("Document already exists: ${source.name}")
        try {
            copyRecursively(source.toPath(), target.toPath())
        } catch (error: IOException) {
            runCatching { deleteRecursively(target.toPath()) }
            throw notFound("Unable to copy document: $documentId", error)
        }
        val copiedId = documentId(target)
        notifyDocumentsChanged(copiedId, targetParentDocumentId)
        return copiedId
    }

    override fun moveDocument(documentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val source = documentFile(documentId)
        val sourceParent = documentFile(sourceParentDocumentId)
        val targetParent = documentFile(targetParentDocumentId)
        if (isRoot(source) || source.parentFile?.canonicalFile != sourceParent.canonicalFile ||
            DocumentPathPolicy.isChild(source.toPath(), targetParent.toPath())
        ) {
            throw FileNotFoundException("Invalid source or destination parent")
        }
        ensureWritableDirectory(sourceParent)
        ensureWritableDirectory(targetParent)
        val target = safeChild(targetParent, source.name)
        if (target.exists()) throw FileNotFoundException("Document already exists: ${source.name}")
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            try {
                Files.move(source.toPath(), target.toPath())
            } catch (error: IOException) {
                throw notFound("Unable to move document: $documentId", error)
            }
        }
        val movedId = documentId(target)
        notifyDocumentsChanged(documentId, sourceParentDocumentId)
        notifyDocumentsChanged(movedId, targetParentDocumentId)
        return movedId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        DocumentPathPolicy.isChild(
            documentFile(parentDocumentId).toPath(),
            documentFile(documentId).toPath(),
        )
    } catch (_: FileNotFoundException) {
        false
    }

    private fun addDocumentRow(cursor: MatrixCursor, file: File) {
        val root = roots().firstOrNull { it.file == file.canonicalFile }
        val row = cursor.newRow()
        cursor.columnNames.forEach { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> row.add(column, documentId(file))
                Document.COLUMN_DISPLAY_NAME -> row.add(column, root?.title ?: file.name)
                Document.COLUMN_MIME_TYPE -> row.add(column, mimeType(file))
                Document.COLUMN_FLAGS -> row.add(column, documentFlags(file))
                Document.COLUMN_SIZE -> row.add(column, if (file.isFile) file.length() else 0L)
                Document.COLUMN_LAST_MODIFIED -> row.add(column, file.lastModified())
                Document.COLUMN_ICON -> row.add(column, android.R.drawable.ic_menu_save)
            }
        }
    }

    private fun rootFlags(root: File): Int {
        var flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_RECENTS or
            Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD
        if (root.canWrite()) flags = flags or Root.FLAG_SUPPORTS_CREATE
        return flags
    }

    private fun documentFlags(file: File): Int {
        if (isRoot(file)) {
            return if (file.canWrite()) Document.FLAG_DIR_SUPPORTS_CREATE else 0
        }
        val parentWritable = file.parentFile?.canWrite() == true
        var flags = Document.FLAG_SUPPORTS_COPY
        if (parentWritable) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE
        }
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        return flags
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun documentFile(documentId: String): File {
        val decoded = DocumentIdCodec.decode(documentId)
            ?: throw FileNotFoundException("Invalid document id")
        val candidate = try {
            Paths.get(decoded).toAbsolutePath().normalize().toFile()
        } catch (error: Exception) {
            throw notFound("Invalid document path", error)
        }
        val candidatePath = candidate.toPath()
        val root = roots().firstOrNull { root ->
            DocumentPathPolicy.isSafePath(candidatePath, root.file.toPath())
        }
        if (root == null) {
            throw FileNotFoundException("Document is outside the exported storage root")
        }
        if (!candidate.exists()) {
            throw FileNotFoundException("Document does not exist")
        }
        return try {
            val canonical = candidate.canonicalFile
            if (!DocumentPathPolicy.isSafePath(canonical.toPath(), root.file.toPath())) {
                throw FileNotFoundException("Document resolves outside the exported storage root")
            }
            canonical
        } catch (error: IOException) {
            throw notFound("Unable to resolve document path", error)
        }
    }

    private fun rootForId(rootId: String): ProviderRoot =
        roots().firstOrNull { it.id == rootId } ?: throw FileNotFoundException("Unknown root: $rootId")

    private fun documentId(file: File): String = DocumentIdCodec.encode(file.canonicalPath)

    private fun safeChild(parent: File, displayName: String): File {
        if (displayName.isBlank() || displayName == "." || displayName == ".." ||
            displayName.contains('/') || displayName.contains('\\') ||
            displayName.any { it.isISOControl() }
        ) {
            throw FileNotFoundException("Invalid document name")
        }
        val target = File(parent, displayName).canonicalFile
        if (target.parentFile?.canonicalFile != parent.canonicalFile ||
            roots().none { DocumentPathPolicy.isWithinRoot(target.toPath(), it.file.toPath()) }
        ) {
            throw FileNotFoundException("Document path is outside the exported storage root")
        }
        return target
    }

    private fun ensureWritableDirectory(directory: File) {
        if (!directory.isDirectory) throw FileNotFoundException("Not a directory")
        if (!directory.canWrite()) throw FileNotFoundException("Directory is read-only")
    }

    private fun walkFiles(root: File, action: (File) -> Unit) {
        try {
            Files.walk(root.toPath()).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (!Files.isSymbolicLink(path)) action(path.toFile())
                }
            }
        } catch (_: IOException) {
            // SAF queries should return the accessible subset of a storage root.
        } catch (_: UncheckedIOException) {
            // Scoped storage can surface inaccessible descendants from the stream iterator.
        } catch (_: SecurityException) {
            // SAF queries should return the accessible subset of a storage root.
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyRecursively(source: Path, target: Path) {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS)
            return
        }
        Files.createDirectory(target)
        Files.newDirectoryStream(source).use { children ->
            for (child in children) {
                if (!Files.isSymbolicLink(child)) {
                    copyRecursively(child, target.resolve(child.fileName))
                }
            }
        }
    }

    private fun notifyDocumentsChanged(documentId: String, parentDocumentId: String?) {
        val resolver = appContext()?.contentResolver ?: return
        runCatching {
            resolver.notifyChange(DocumentsContract.buildDocumentUri(authority(), documentId), null)
            parentDocumentId?.let {
                resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(), it), null)
            }
        }
    }

    private fun roots(): List<ProviderRoot> {
        @Suppress("DEPRECATION")
        val external = rootOverride ?: Environment.getExternalStorageDirectory()
        if (!external.exists() || !external.isDirectory) return emptyList()
        val canonical = runCatching { external.canonicalFile }.getOrNull() ?: return emptyList()
        val title = appContext()?.getString(DesignSystemR.string.local_storage) ?: return emptyList()
        return listOf(ProviderRoot(documentId(canonical), canonical, title))
    }

    private fun authority(): String = "${appContext()?.packageName ?: "com.explorer.fileexplorer"}.documents"

    private fun appContext() = context

    private fun isRoot(file: File): Boolean = roots().any { it.file == file.canonicalFile }

    private fun notFound(message: String, cause: Throwable): FileNotFoundException =
        FileNotFoundException(message).also { it.initCause(cause) }

    private data class ProviderRoot(val id: String, val file: File, val title: String)

    private companion object {
        const val MAX_RECENT_DOCUMENTS = 64
        const val MAX_SEARCH_RESULTS = 100
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_ICON,
        )
    }
}
