package com.explorer.fileexplorer.core.data

import android.content.Context
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles archive browsing (as virtual folders), extraction, and creation.
 * Supports: ZIP (via zip4j for encryption), TAR, GZ, BZ2, XZ, 7z, Zstandard.
 */
@Singleton
class ArchiveHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** List entries in an archive at a given internal path. */
    suspend fun listArchive(
        archivePath: String,
        internalPath: String = "",
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val ext = archivePath.substringAfterLast('.').lowercase()
        val allEntries = when {
            ext == "zip" || ext == "jar" || ext == "war" || ext == "ear" -> listZipEntries(archivePath)
            ext == "7z" -> list7zEntries(archivePath)
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
            val count = when {
                ext == "zip" || ext == "jar" -> extractZip(archivePath, destination, entriesToExtract, password, onProgress)
                ext == "7z" -> extract7z(archivePath, destination, entriesToExtract, onProgress)
                ext in setOf("tar", "tgz", "tbz2", "txz") || isTarCompressed(archivePath) ->
                    extractTar(archivePath, destination, entriesToExtract, onProgress)
                else -> 0
            }
            Result.success(count)
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
            "bz2", "tbz2", "xz", "txz", "zst")
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

    private fun extractZip(
        archivePath: String, destination: String,
        entries: List<String>?, password: CharArray?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val zipFile = ZipFile(archivePath)
        if (password != null) zipFile.setPassword(password)

        var count = 0
        if (entries == null) {
            zipFile.extractAll(destination)
            count = zipFile.fileHeaders.size
        } else {
            for (entry in entries) {
                val header = zipFile.getFileHeader(entry) ?: continue
                zipFile.extractFile(header, destination)
                onProgress(0, 0, header.fileName)
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

    private fun extract7z(
        archivePath: String, destination: String,
        entries: List<String>?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        var count = 0
        SevenZFile.builder().setFile(File(archivePath)).get().use { sevenZ ->
            var entry: SevenZArchiveEntry?
            while (sevenZ.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                if (entries != null && e.name.trimEnd('/') !in entries) continue

                val outFile = File(destination, e.name)
                if (e.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (sevenZ.read(buf).also { len = it } != -1) {
                            fos.write(buf, 0, len)
                        }
                    }
                    onProgress(0, 0, e.name)
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

    private fun extractTar(
        archivePath: String, destination: String,
        entries: List<String>?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        var count = 0
        val stream = TarArchiveInputStream(openTarInputStream(archivePath))
        stream.use { tar ->
            var entry: ArchiveEntry?
            while (tar.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                if (entries != null && e.name.trimEnd('/') !in entries) continue

                val outFile = File(destination, e.name)
                // Protect against zip-slip
                if (!outFile.canonicalPath.startsWith(File(destination).canonicalPath)) continue

                if (e.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (tar.read(buf).also { len = it } != -1) {
                            fos.write(buf, 0, len)
                        }
                    }
                    onProgress(0, 0, e.name)
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
