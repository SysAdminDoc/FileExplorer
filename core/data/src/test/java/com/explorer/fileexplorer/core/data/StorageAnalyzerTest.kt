package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageAnalyzerTest {

    @Test
    fun scanReportsSizesLargestFilesAndDuplicateContent() = runBlocking {
        val root = Files.createTempDirectory("fileexplorer-analyzer")
        try {
            val photos = Files.createDirectories(root.resolve("Photos"))
            val duplicate = byteArrayOf(1, 2, 3, 4)
            photos.resolve("one.bin").writeBytes(duplicate)
            root.resolve("two.bin").writeBytes(duplicate)
            root.resolve("large.bin").writeBytes(ByteArray(12) { 7 })

            val result = StorageAnalyzer().scan(root.toString())

            assertEquals(3, result.fileCount)
            assertEquals(1, result.directoryCount)
            assertEquals(20, result.totalBytes)
            assertEquals("large.bin", result.largestFiles.first().name)
            assertEquals(1, result.duplicateGroups.size)
            assertEquals(2, result.duplicateGroups.first().files.size)
            assertTrue(result.root.children.any { it.name == "Photos" })
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }
}
