package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            assertTrue(result.hashAnalysisComplete)
            assertEquals(0, result.skippedFiles)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }

    @Test
    fun boundedScanReturnsMetadataOnlyCheckpointThatCanResume() = runBlocking {
        val root = Files.createTempDirectory("fileexplorer-analyzer-resume")
        try {
            root.resolve("one.txt").writeBytes(byteArrayOf(1))
            root.resolve("two.txt").writeBytes(byteArrayOf(2))
            root.resolve("three.txt").writeBytes(byteArrayOf(3))

            val analyzer = StorageAnalyzer()
            val bounded = analyzer.scan(
                root.toString(),
                limits = StorageScanLimits(
                    maxFiles = 2,
                    maxDirectories = 10,
                    maxIndexedBytes = 100,
                    maxHashBytes = 100,
                    maxHashCandidates = 10,
                ),
            )

            assertFalse(bounded.isComplete)
            val checkpoint = assertNotNull(bounded.checkpoint)
            assertTrue(checkpoint.files.all { it.path.contains("fileexplorer-analyzer-resume") })
            assertTrue(checkpoint.sampleHashes.values.none { it.contains("one") || it.contains("two") })

            val resumed = analyzer.scan(root.toString(), resumeFrom = checkpoint)
            assertTrue(resumed.isComplete)
            assertEquals(3, resumed.fileCount)
            assertEquals(3, resumed.totalBytes)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }

    @Test
    fun hashBudgetLeavesAnalysisResumableWithoutRetainingContents() = runBlocking {
        val root = Files.createTempDirectory("fileexplorer-analyzer-budget")
        try {
            val payload = ByteArray(32) { it.toByte() }
            root.resolve("one.bin").writeBytes(payload)
            root.resolve("two.bin").writeBytes(payload)

            val result = StorageAnalyzer().scan(
                root.toString(),
                limits = StorageScanLimits(
                    maxFiles = 10,
                    maxDirectories = 10,
                    maxIndexedBytes = 1_000,
                    maxHashBytes = 32,
                    maxHashCandidates = 10,
                    sampleBytes = 16,
                ),
            )

            assertFalse(result.hashAnalysisComplete)
            assertNotNull(result.checkpoint)
            assertTrue(result.checkpoint!!.files.all { it.size == payload.size.toLong() })
            assertTrue(result.checkpoint!!.sampleHashes.values.all { it.length == 64 })
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }
}
