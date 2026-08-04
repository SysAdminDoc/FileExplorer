package com.explorer.fileexplorer.feature.security

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IntegrityHasherTest {
    @Test
    fun fileFingerprintChangesWhenContentsChange() = runBlocking {
        val file = Files.createTempFile("file-explorer-integrity", ".txt").toFile()
        try {
            file.writeText("before")
            val before = IntegrityHasher.fingerprint(file.absolutePath).getOrThrow()
            file.writeText("after")
            val after = IntegrityHasher.fingerprint(file.absolutePath).getOrThrow()

            assertNotEquals(before.sha256, after.sha256)
            assertTrue(!after.isDirectory)
        } finally {
            file.delete()
        }
    }

    @Test
    fun directoryFingerprintIncludesNamesAndContents() = runBlocking {
        val directory = Files.createTempDirectory("file-explorer-integrity-dir").toFile()
        try {
            val child = File(directory, "child.txt")
            child.writeText("before")
            val before = IntegrityHasher.fingerprint(directory.absolutePath).getOrThrow()
            child.writeText("after")
            val after = IntegrityHasher.fingerprint(directory.absolutePath).getOrThrow()

            assertNotEquals(before.sha256, after.sha256)
            assertTrue(after.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingPathReturnsFailure() = runBlocking {
        val result = IntegrityHasher.fingerprint("${System.getProperty("java.io.tmpdir")}\\missing-integrity-path")

        assertTrue(result.isFailure)
    }
}
