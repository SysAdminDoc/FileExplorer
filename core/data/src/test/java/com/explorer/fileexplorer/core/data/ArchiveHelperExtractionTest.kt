package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveHelperExtractionTest {
    private val tempRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    @Test
    fun zipExtractionStagesAndCommitsSafeEntries() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("safe.zip").toFile()
        createZip(archive, "folder/file.txt" to "safe")
        val destination = root.resolve("destination").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertEquals(1, result.getOrThrow())
        assertEquals("safe", destination.resolve("folder/file.txt").readText())
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".fileexplorer-extract-") })
    }

    @Test
    fun zipTraversalFailsBeforeCommittingAnyOutput() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("traversal.zip").toFile()
        createZip(archive, "../outside.txt" to "blocked")
        val destination = root.resolve("destination").toFile()
        val outside = root.resolve("outside.txt").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertFalse(outside.exists())
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".fileexplorer-extract-") })
    }

    @Test
    fun sevenZipTraversalFailsBeforeCommittingAnyOutput() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("traversal.7z").toFile()
        SevenZOutputFile(archive).use { output ->
            val entry = SevenZArchiveEntry().apply {
                name = "../outside.txt"
                size = 7
            }
            output.putArchiveEntry(entry)
            output.write("blocked".toByteArray())
            output.closeArchiveEntry()
        }
        val destination = root.resolve("destination").toFile()
        val outside = root.resolve("outside.txt").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertFalse(outside.exists())
    }

    @Test
    fun tarSymbolicLinkIsRejected() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("link.tar").toFile()
        TarArchiveOutputStream(FileOutputStream(archive)).use { output ->
            val entry = TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply {
                linkName = "../outside.txt"
            }
            output.putArchiveEntry(entry)
            output.closeArchiveEntry()
        }
        val destination = root.resolve("destination").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".fileexplorer-extract-") })
    }

    @Test
    fun policyRejectsAbsoluteAndBackslashTraversalPaths() {
        val root = tempRoot()
        val tooDeep = (1..(ArchiveEntryPathPolicy.MAX_ENTRY_DEPTH + 1)).joinToString("/") { "folder" }

        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "/outside.txt") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "C:\\outside.txt") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "folder\\..\\outside.txt") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "bad\u0000name") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), tooDeep) != null)
    }

    @Test
    fun budgetRejectsOversizedAndTooManyEntries() {
        val oversized = ArchiveExtractionBudget()
        assertFailsWith<ArchiveExtractionException> {
            oversized.register("large.bin", ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES + 1)
        }

        val tooMuchData = ArchiveExtractionBudget()
        repeat(
            (ArchiveEntryPathPolicy.MAX_TOTAL_UNCOMPRESSED_BYTES / ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES)
                .toInt(),
        ) {
            tooMuchData.register("data-$it", ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES)
        }
        assertFailsWith<ArchiveExtractionException> {
            tooMuchData.register("data-over-limit", 1)
        }

        val tooMany = ArchiveExtractionBudget()
        repeat(ArchiveEntryPathPolicy.MAX_ENTRIES) {
            tooMany.register("entry-$it", 0)
        }
        assertFailsWith<ArchiveExtractionException> {
            tooMany.register("one-too-many", 0)
        }
    }

    private fun tempRoot(): Path = Files.createTempDirectory("fileexplorer-archive-test").also(tempRoots::add)

    private fun createZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }
}
