package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureDeleteTest {
    private val temps = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        temps.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    @Test
    fun secureDeleteRemovesFile() = runBlocking {
        val f = Files.createTempFile("secdel", ".txt").also { temps.add(it) }
        Files.write(f, "secret data".toByteArray())
        assertTrue(Files.exists(f))

        val result = SecureDelete.secureDelete(f.toString())

        assertTrue(result.isSuccess)
        assertFalse(Files.exists(f))
    }

    @Test
    fun secureDeleteHandlesEmptyFile() = runBlocking {
        val f = Files.createTempFile("secdel-empty", ".txt").also { temps.add(it) }
        assertTrue(Files.size(f) == 0L)

        val result = SecureDelete.secureDelete(f.toString())

        assertTrue(result.isSuccess)
        assertFalse(Files.exists(f))
    }

    @Test
    fun secureDeleteRemovesDirectory() = runBlocking {
        val dir = Files.createTempDirectory("secdel-dir")
        val child = dir.resolve("file.txt")
        Files.write(child, "data".toByteArray())

        val result = SecureDelete.secureDelete(dir.toString())

        assertTrue(result.isSuccess)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun secureDeleteFailsForMissingFile() = runBlocking {
        val result = SecureDelete.secureDelete("/nonexistent/path/xyz.tmp")
        assertTrue(result.isFailure)
    }

    @Test
    fun secureDeleteRejectsNonPositivePasses() = runBlocking {
        val f = Files.createTempFile("secdel-passes", ".txt").also { temps.add(it) }
        Files.write(f, "secret data".toByteArray())

        val result = SecureDelete.secureDelete(f.toString(), passes = 0)

        assertTrue(result.isFailure)
        assertTrue(Files.exists(f))
    }

    @Test
    fun secureDeleteRejectsBlankPath() = runBlocking {
        val result = SecureDelete.secureDelete(" ")

        assertTrue(result.isFailure)
    }
}
