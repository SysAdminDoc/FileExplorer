package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalTrashManagerTest {
    private val manager = LocalTrashManager()
    private val tempRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempRoots.forEach { root -> deleteRecursive(root) }
        tempRoots.clear()
    }

    @Test
    fun moveToTrashMovesFileAndRecordsMetadata() = runBlocking {
        val root = tempRoot()
        val file = root.resolve("report.txt")
        Files.write(file, "contents".toByteArray())

        val result = manager.moveToTrash(listOf(file.toString()), listOf(root.toString()))

        assertEquals(1, result.getOrThrow())
        assertFalse(Files.exists(file))
        val items = manager.listTrashItems(listOf(root.toString())).getOrThrow()
        assertEquals(1, items.size)
        assertEquals("report.txt", items.single().name)
        assertEquals(file.toAbsolutePath().normalize().toString(), items.single().originalPath)
        assertTrue(Files.exists(root.resolve(LocalTrashManager.TRASH_DIR_NAME)))
    }

    @Test
    fun restoreMovesItemBackToOriginalPath() = runBlocking {
        val root = tempRoot()
        val file = root.resolve("draft.md")
        Files.write(file, "draft".toByteArray())
        manager.moveToTrash(listOf(file.toString()), listOf(root.toString())).getOrThrow()
        val item = manager.listTrashItems(listOf(root.toString())).getOrThrow().single()

        val restored = manager.restore(listOf(item.id), listOf(root.toString()))

        assertEquals(1, restored.getOrThrow())
        assertTrue(Files.exists(file))
        assertEquals("draft", String(Files.readAllBytes(file)))
        assertTrue(manager.listTrashItems(listOf(root.toString())).getOrThrow().isEmpty())
    }

    @Test
    fun permanentlyDeleteRemovesTrashPayloadAndMetadata() = runBlocking {
        val root = tempRoot()
        val dir = root.resolve("folder")
        Files.createDirectories(dir)
        Files.write(dir.resolve("nested.txt"), "nested".toByteArray())
        manager.moveToTrash(listOf(dir.toString()), listOf(root.toString())).getOrThrow()
        val item = manager.listTrashItems(listOf(root.toString())).getOrThrow().single()

        val deleted = manager.permanentlyDelete(listOf(item.id), listOf(root.toString()))

        assertEquals(1, deleted.getOrThrow())
        assertFalse(Files.exists(dir))
        assertTrue(manager.listTrashItems(listOf(root.toString())).getOrThrow().isEmpty())
    }

    @Test
    fun purgeExpiredDeletesItemsAtOrPastTtlCutoff() = runBlocking {
        val root = tempRoot()
        val file = root.resolve("old.bin")
        Files.write(file, "old".toByteArray())
        manager.moveToTrash(listOf(file.toString()), listOf(root.toString())).getOrThrow()

        val purged = manager.purgeExpired(ttlDays = 0, volumeRoots = listOf(root.toString()))

        assertEquals(1, purged.getOrThrow())
        assertTrue(manager.listTrashItems(listOf(root.toString())).getOrThrow().isEmpty())
    }

    private fun tempRoot(): Path {
        return Files.createTempDirectory("fileexplorer-trash-test").also { tempRoots.add(it) }
    }

    private fun deleteRecursive(path: Path) {
        if (!Files.exists(path)) return
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { stream ->
                for (entry in stream) deleteRecursive(entry)
            }
        }
        Files.deleteIfExists(path)
    }
}
