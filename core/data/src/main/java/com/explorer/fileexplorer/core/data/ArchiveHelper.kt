package com.explorer.fileexplorer.core.data

import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.FileItem
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles archive browsing (as virtual folders), extraction, and creation.
 * Supports: ZIP (via zip4j for encryption), RAR (read-only), TAR, GZ, BZ2, XZ, 7z, Zstandard.
 */
@Singleton
class ArchiveHelper @Inject constructor() {
    /** List entries in an archive at a given internal path. */
    suspend fun listArchive(
        archivePath: String,
        internalPath: String = "",
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val ext = archivePath.substringAfterLast('.').lowercase()
        val allEntries = when {
            ext == "zip" || ext == "jar" || ext == "war" || ext == "ear" -> listZipEntries(archivePath)
            ext == "7z" -> list7zEntries(archivePath)
            ext == "rar" -> listRarEntries(archivePath)
            ext in setOf("tar", "tgz", "tbz2", "txz") || isTarCompressed(archivePath) -> listTarEntries(archivePath)
            else -> emptyList()
        }

        // Filter to show only entries at the requested internal path level
        val prefix = if (internalPath.isEmpty()) "" else internalPath.trimEnd('/') + "/"
        val directChildren = mutableMapOf<String, FileItem>()

        for (entry in allEntries) {
            val relativePath = if (prefix.isEmpty()) entry.path else {
                if (!entry.path.startsWith(prefix)) continue
                entry.path.removePrefix(prefix)
            }
            if (relativePath.isEmpty()) continue

            val parts = relativePath.trimEnd('/').split('/')
            val childName = parts.first()

            if (parts.size == 1) {
                // Direct child file or empty directory
                directChildren[childName] = entry.copy(
                    name = childName,
                    path = if (prefix.isEmpty()) childName else "$prefix$childName",
                )
            } else if (childName !in directChildren) {
                // Implicit directory from nested entries
                directChildren[childName] = FileItem(
                    name = childName,
                    path = if (prefix.isEmpty()) childName else "$prefix$childName",
                    isDirectory = true,
                    mimeType = "inode/directory",
                )
            }
        }

        directChildren.values.toList().sortedWith(
            compareBy<FileItem> { if (it.isDirectory) 0 else 1 }
                .thenBy { it.name.lowercase() }
        )
    }

    /** Extract specific entries or all entries from an archive. */
    suspend fun extract(
        archivePath: String,
        destination: String,
        entriesToExtract: List<String>? = null, // null = extract all
        password: CharArray? = null,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val ext = archivePath.substringAfterLast('.').lowercase()
            val destinationRoot = prepareDestination(destination)
            val stagingRoot = Files.createTempDirectory(destinationRoot.toPath(), ".fileexplorer-extract-").toFile()
            val count = try {
                val extractedCount = when {
                    ext == "zip" || ext == "jar" -> extractZip(
                        archivePath,
                        stagingRoot,
                        entriesToExtract,
                        password,
                        onProgress,
                    )
                    ext == "7z" -> extract7z(archivePath, stagingRoot, entriesToExtract, onProgress)
                    ext == "rar" -> extractRar(archivePath, stagingRoot, entriesToExtract, password, onProgress)
                    ext in setOf("tar", "tgz", "tbz2", "txz") || isTarCompressed(archivePath) ->
                        extractTar(archivePath, stagingRoot, entriesToExtract, onProgress)
                    else -> 0
                }
                commitStagedExtraction(stagingRoot, destinationRoot)
                extractedCount
            } finally {
                stagingRoot.deleteRecursively()
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Create an archive from files. */
    suspend fun createArchive(
        outputPath: String,
        sourcePaths: List<String>,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        password: CharArray? = null,
        compressionLevel: Int = 5,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (format) {
                ArchiveFormat.ZIP -> createZip(outputPath, sourcePaths, password, compressionLevel, onProgress)
                ArchiveFormat.SEVEN_Z -> create7z(outputPath, sourcePaths, compressionLevel, onProgress)
                ArchiveFormat.TAR_GZ -> createTarGz(outputPath, sourcePaths, onProgress)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Check if a file is a supported archive. */
    fun isArchive(path: String): Boolean {
        val ext = path.substringAfterLast('.').lowercase()
        return ext in setOf("zip", "jar", "war", "ear", "7z", "tar", "gz", "tgz",
            "bz2", "tbz2", "xz", "txz", "zst", "rar")
    }

    private fun prepareDestination(destination: String): File {
        val root = File(destination).canonicalFile
        if (root.exists()) {
            if (!root.isDirectory) throw ArchiveExtractionException("Extraction destination is not a directory: $destination")
        } else if (!root.mkdirs() && !root.isDirectory) {
            throw ArchiveExtractionException("Unable to create extraction destination: $destination")
        }
        return root
    }

    private fun normalizedSelection(entries: List<String>?): Set<String>? {
        return entries?.map { entry ->
            ArchiveEntryPathPolicy.normalizeEntryName(entry)
                ?: throw ArchiveExtractionException("Invalid requested archive entry: $entry")
        }?.toSet()
    }

    private fun stagedEntry(
        stagingRoot: File,
        entryName: String,
        declaredSize: Long,
        budget: ArchiveExtractionBudget,
    ): File {
        val normalizedName = ArchiveEntryPathPolicy.normalizeEntryName(entryName)
            ?: throw ArchiveExtractionException("Unsafe archive entry path: $entryName")
        val output = ArchiveEntryPathPolicy.safeDestination(stagingRoot.path, normalizedName)
            ?: throw ArchiveExtractionException("Unsafe archive entry path: $entryName")
        budget.register(normalizedName, declaredSize)
        return output
    }

    private suspend fun copyArchiveEntry(
        input: InputStream,
        outputFile: File,
        entryName: String,
        declaredSize: Long,
        budget: ArchiveExtractionBudget,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw ArchiveExtractionException("Unable to create extraction directory: ${parent.path}")
            }
        }
        FileOutputStream(outputFile).use { fileOutput ->
            val boundedOutput = BoundedArchiveOutputStream(
                delegate = fileOutput,
                entryName = entryName,
                declaredSize = declaredSize,
                budget = budget,
                coroutineContext = coroutineContext,
                onProgress = onProgress,
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            input.use { source ->
                while (true) {
                    coroutineContext.ensureActive()
                    val length = source.read(buffer)
                    if (length == -1) break
                    boundedOutput.write(buffer, 0, length)
                }
            }
            boundedOutput.finish()
        }
    }

    private fun commitStagedExtraction(stagingRoot: File, destinationRoot: File) {
        val stagedEntries = stagingRoot.walkTopDown()
            .filter { it != stagingRoot }
            .toList()
            .sortedWith(compareBy<File> { it.toPath().nameCount }.thenBy { if (it.isDirectory) 0 else 1 })

        for (source in stagedEntries) {
            if (Files.isSymbolicLink(source.toPath())) {
                throw ArchiveExtractionException("Staged symbolic links are not supported: ${source.name}")
            }
            val relativeName = stagingRoot.toPath().relativize(source.toPath()).toString()
            val target = ArchiveEntryPathPolicy.safeDestination(destinationRoot.path, relativeName)
                ?: throw ArchiveExtractionException("Unsafe archive entry path: $relativeName")
            if (Files.isSymbolicLink(target.toPath())) {
                throw ArchiveExtractionException("Refusing to overwrite a symbolic link: ${target.path}")
            }

            if (source.isDirectory) {
                if (target.exists() && !target.isDirectory) {
                    throw ArchiveExtractionException("Extraction target is not a directory: ${target.path}")
                }
                if (!target.exists() && !target.mkdirs() && !target.isDirectory) {
                    throw ArchiveExtractionException("Unable to create extraction directory: ${target.path}")
                }
            } else {
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw ArchiveExtractionException("Unable to create extraction directory: ${parent.path}")
                    }
                }
                try {
                    Files.move(
                        source.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private class BoundedArchiveOutputStream(
        private val delegate: OutputStream,
        private val entryName: String,
        private val declaredSize: Long,
        private val budget: ArchiveExtractionBudget,
        private val coroutineContext: CoroutineContext,
        private val onProgress: (Long, Long, String) -> Unit,
    ) : OutputStream() {
        private var entryBytes = 0L

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            coroutineContext.ensureActive()
            if (length == 0) return
            val nextEntryBytes = entryBytes + length
            budget.consume(entryName, nextEntryBytes, declaredSize, length)
            delegate.write(bytes, offset, length)
            entryBytes = nextEntryBytes
            onProgress(budget.writtenBytes, budget.progressTotal, entryName)
        }

        fun finish() {
            budget.finish(entryName, entryBytes, declaredSize)
        }
    }

    private class SevenZEntryInputStream(
        private val archive: SevenZFile,
    ) : InputStream() {
        override fun read(): Int = archive.read()

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = archive.read(bytes, offset, length)

        override fun close() {
            // The enclosing SevenZFile owns the archive stream and closes it.
        }
    }

    // -- ZIP (via zip4j for encryption support) --

    private fun listZipEntries(path: String): List<FileItem> {
        val zipFile = ZipFile(path)
        return zipFile.fileHeaders.map { header ->
            val name = header.fileName.trimEnd('/')
            val ext = name.substringAfterLast('.', "")
            val mime = if (header.isDirectory) "inode/directory"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            FileItem(
                name = name.substringAfterLast('/'),
                path = header.fileName.trimEnd('/'),
                size = header.uncompressedSize,
                lastModified = header.lastModifiedTimeEpoch,
                isDirectory = header.isDirectory,
                mimeType = mime,
                extension = ext,
            )
        }
    }

    private suspend fun extractZip(
        archivePath: String, stagingRoot: File,
        entries: List<String>?, password: CharArray?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        var count = 0
        ZipFile(archivePath).use { zipFile ->
            if (password != null) zipFile.setPassword(password)
            for (header in zipFile.fileHeaders) {
                val entryName = header.fileName.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue

                val outputFile = stagedEntry(stagingRoot, entryName, header.uncompressedSize, budget)
                if (header.isDirectory) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw ArchiveExtractionException("Unable to create extraction directory: ${outputFile.path}")
                    }
                } else {
                    copyArchiveEntry(
                        input = zipFile.getInputStream(header),
                        outputFile = outputFile,
                        entryName = entryName,
                        declaredSize = header.uncompressedSize,
                        budget = budget,
                        onProgress = onProgress,
                    )
                }
                count++
            }
        }
        return count
    }

    private fun createZip(
        outputPath: String, sourcePaths: List<String>,
        password: CharArray?, level: Int,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val zipFile = ZipFile(outputPath)
        if (password != null) zipFile.setPassword(password)

        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = when {
                level <= 1 -> CompressionLevel.FASTEST
                level <= 3 -> CompressionLevel.FAST
                level <= 7 -> CompressionLevel.NORMAL
                else -> CompressionLevel.MAXIMUM
            }
            if (password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }

        for (sourcePath in sourcePaths) {
            val file = File(sourcePath)
            onProgress(0, 0, file.name)
            if (file.isDirectory) {
                zipFile.addFolder(file, params)
            } else {
                zipFile.addFile(file, params)
            }
        }
    }

    // -- 7z (via Commons Compress) --

    private fun list7zEntries(path: String): List<FileItem> {
        val entries = mutableListOf<FileItem>()
        SevenZFile.builder().setFile(File(path)).get().use { sevenZ ->
            var entry: SevenZArchiveEntry?
            while (sevenZ.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val name = e.name.trimEnd('/')
                val ext = name.substringAfterLast('.', "")
                val mime = if (e.isDirectory) "inode/directory"
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
                entries.add(
                    FileItem(
                        name = name.substringAfterLast('/'),
                        path = e.name.trimEnd('/'),
                        size = e.size,
                        lastModified = e.lastModifiedDate?.time ?: 0L,
                        isDirectory = e.isDirectory,
                        mimeType = mime,
                        extension = ext,
                    )
                )
            }
        }
        return entries
    }

    private suspend fun extract7z(
        archivePath: String, stagingRoot: File,
        entries: List<String>?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        var count = 0
        SevenZFile.builder().setFile(File(archivePath)).get().use { sevenZ ->
            var entry: SevenZArchiveEntry?
            while (sevenZ.nextEntry.also { entry = it } != null) {
                coroutineContext.ensureActive()
                val e = entry ?: continue
                val entryName = e.name.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue

                val outFile = stagedEntry(stagingRoot, entryName, e.size, budget)
                if (e.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw ArchiveExtractionException("Unable to create extraction directory: ${outFile.path}")
                    }
                } else {
                    copyArchiveEntry(
                        input = SevenZEntryInputStream(sevenZ),
                        outputFile = outFile,
                        entryName = entryName,
                        declaredSize = e.size,
                        budget = budget,
                        onProgress = onProgress,
                    )
                }
                count++
            }
        }
        return count
    }

    private fun create7z(
        outputPath: String, sourcePaths: List<String>,
        level: Int,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val output = org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(File(outputPath))
        output.use { out ->
            for (sourcePath in sourcePaths) {
                addTo7z(out, File(sourcePath), "", onProgress)
            }
        }
    }

    private fun addTo7z(
        out: org.apache.commons.compress.archivers.sevenz.SevenZOutputFile,
        file: File, base: String,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"
        if (file.isDirectory) {
            val entry = out.createArchiveEntry(file, "$entryName/")
            out.putArchiveEntry(entry)
            out.closeArchiveEntry()
            file.listFiles()?.forEach { child -> addTo7z(out, child, entryName, onProgress) }
        } else {
            val entry = out.createArchiveEntry(file, entryName)
            out.putArchiveEntry(entry)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var len: Int
                while (fis.read(buf).also { len = it } != -1) {
                    out.write(buf, 0, len)
                }
            }
            out.closeArchiveEntry()
            onProgress(0, 0, file.name)
        }
    }

    // -- RAR (read-only via Junrar) --

    private fun listRarEntries(path: String): List<FileItem> {
        val entries = mutableListOf<FileItem>()
        Archive(File(path)).use { archive ->
            for (header in archive) {
                entries += header.toFileItem()
            }
        }
        return entries
    }

    private suspend fun extractRar(
        archivePath: String,
        stagingRoot: File,
        entries: List<String>?,
        password: CharArray?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        val archive = if (password == null) {
            Archive(File(archivePath))
        } else {
            Archive(File(archivePath), String(password))
        }
        var count = 0
        archive.use {
            for (header in archive) {
                coroutineContext.ensureActive()
                val entryName = header.fileName.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue

                val outputFile = stagedEntry(stagingRoot, entryName, header.fullUnpackSize, budget)
                if (header.isDirectory) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw ArchiveExtractionException("Unable to create extraction directory: ${outputFile.path}")
                    }
                } else {
                    outputFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw ArchiveExtractionException("Unable to create extraction directory: ${parent.path}")
                        }
                    }
                    FileOutputStream(outputFile).use { output ->
                        val boundedOutput = BoundedArchiveOutputStream(
                            delegate = output,
                            entryName = entryName,
                            declaredSize = header.fullUnpackSize,
                            budget = budget,
                            coroutineContext = coroutineContext,
                            onProgress = onProgress,
                        )
                        archive.extractFile(header, boundedOutput)
                        boundedOutput.finish()
                    }
                }
                count++
            }
        }
        return count
    }

    private fun FileHeader.toFileItem(): FileItem {
        val name = fileName.trimEnd('/')
        val extension = name.substringAfterLast('.', "")
        val mime = if (isDirectory) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
        return FileItem(
            name = name.substringAfterLast('/'),
            path = name,
            size = fullUnpackSize,
            lastModified = mTime?.time ?: 0L,
            isDirectory = isDirectory,
            mimeType = mime,
            extension = extension,
        )
    }

    // -- TAR (with compression auto-detection) --

    private fun isTarCompressed(path: String): Boolean {
        val ext = path.substringAfterLast('.').lowercase()
        return ext in setOf("gz", "bz2", "xz", "zst") ||
                path.substringBeforeLast('.').substringAfterLast('.').lowercase() == "tar"
    }

    private fun openTarInputStream(path: String): InputStream {
        val fis = BufferedInputStream(FileInputStream(path))
        val ext = path.substringAfterLast('.').lowercase()
        return when (ext) {
            "gz", "tgz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, fis)
            "bz2", "tbz2" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, fis)
            "xz", "txz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, fis)
            "zst" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.ZSTANDARD, fis)
            else -> fis // Plain .tar
        }
    }

    private fun listTarEntries(path: String): List<FileItem> {
        val entries = mutableListOf<FileItem>()
        val stream = TarArchiveInputStream(openTarInputStream(path))
        stream.use { tar ->
            var entry: ArchiveEntry?
            while (tar.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val name = e.name.trimEnd('/')
                if (name.isEmpty()) continue
                val ext = name.substringAfterLast('.', "")
                val mime = if (e.isDirectory) "inode/directory"
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
                entries.add(
                    FileItem(
                        name = name.substringAfterLast('/'),
                        path = e.name.trimEnd('/'),
                        size = e.size,
                        lastModified = e.lastModifiedDate?.time ?: 0L,
                        isDirectory = e.isDirectory,
                        mimeType = mime,
                        extension = ext,
                    )
                )
            }
        }
        return entries
    }

    private suspend fun extractTar(
        archivePath: String, stagingRoot: File,
        entries: List<String>?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        var count = 0
        val stream = TarArchiveInputStream(openTarInputStream(archivePath))
        stream.use { tar ->
            var entry: ArchiveEntry?
            while (tar.nextEntry.also { entry = it } != null) {
                coroutineContext.ensureActive()
                val e = entry ?: continue
                val entryName = e.name.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue
                val tarEntry = e as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                    ?: throw ArchiveExtractionException("Unsupported TAR entry: $entryName")
                if (tarEntry.isSymbolicLink || tarEntry.isLink || !tarEntry.isDirectory && !tarEntry.isFile) {
                    throw ArchiveExtractionException("Symbolic, hard-link, or special TAR entry is not supported: $entryName")
                }

                val outFile = stagedEntry(stagingRoot, entryName, e.size, budget)

                if (e.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw ArchiveExtractionException("Unable to create extraction directory: ${outFile.path}")
                    }
                } else {
                    copyArchiveEntry(
                        input = tar,
                        outputFile = outFile,
                        entryName = entryName,
                        declaredSize = e.size,
                        budget = budget,
                        onProgress = onProgress,
                    )
                }
                count++
            }
        }
        return count
    }

    private fun createTarGz(
        outputPath: String, sourcePaths: List<String>,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val fos = FileOutputStream(outputPath)
        val gzos = CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.GZIP, fos)
        val tarOut = ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.TAR, gzos)
            as org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
        tarOut.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
        tarOut.setBigNumberMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.BIGNUMBER_POSIX)

        tarOut.use { out ->
            for (sourcePath in sourcePaths) {
                addToTar(out, File(sourcePath), "", onProgress)
            }
        }
    }

    private fun addToTar(
        out: org.apache.commons.compress.archivers.tar.TarArchiveOutputStream,
        file: File, base: String,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"
        if (file.isDirectory) {
            val entry = out.createArchiveEntry(file, "$entryName/")
            out.putArchiveEntry(entry)
            out.closeArchiveEntry()
            file.listFiles()?.forEach { child -> addToTar(out, child, entryName, onProgress) }
        } else {
            val entry = out.createArchiveEntry(file, entryName)
            out.putArchiveEntry(entry)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var len: Int
                while (fis.read(buf).also { len = it } != -1) {
                    out.write(buf, 0, len)
                }
            }
            out.closeArchiveEntry()
            onProgress(0, 0, file.name)
        }
    }
}

enum class ArchiveFormat(val extension: String) {
    ZIP("zip"),
    SEVEN_Z("7z"),
    TAR_GZ("tar.gz"),
}
