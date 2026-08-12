package com.explorer.fileexplorer.core.storage

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileOperationStagingTest {
    private val roots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        roots.forEach { FileOperationStaging.deleteRecursively(it.toFile()) }
        roots.clear()
    }

    @Test
    fun interruptedMoveRestoresTheOriginalPath() {
        val root = tempRoot()
        val original = root.resolve("source.txt").toFile().apply { writeText("payload") }
        val operation = FileOperationStaging.createOperation(root.toFile(), "move", StagingRecoveryPolicy.ROLLBACK)
        val staged = operation.toPath().resolve("payload/source.txt")
        Files.createDirectories(staged.parent)
        FileOperationStaging.writeManifest(
            operation,
            StagingManifest(
                "move",
                StagingRecoveryPolicy.ROLLBACK,
                listOf(StagedEntry(original.path, staged.toString(), "/destination/source.txt", FileOperationStaging.STATE_STAGED)),
            ),
        )
        FileOperationStaging.atomicMove(original.toPath(), staged, replaceExisting = false)

        assertEquals(1, FileOperationStaging.recoverOrphaned(root.toFile()))
        assertEquals("payload", original.readText())
        assertFalse(operation.exists())
    }

    @Test
    fun interruptedCopyRemovesTheUncommittedDestination() {
        val root = tempRoot()
        val source = root.resolve("source.txt").toFile().apply { writeText("source") }
        val target = root.resolve("target.txt").toFile().apply { writeText("new") }
        val operation = FileOperationStaging.createOperation(root.toFile(), "copy", StagingRecoveryPolicy.ROLLBACK)
        val staged = operation.toPath().resolve("payload/source.txt")
        Files.createDirectories(staged.parent)
        staged.toFile().writeText("staged")
        FileOperationStaging.writeManifest(
            operation,
            StagingManifest(
                "copy",
                StagingRecoveryPolicy.ROLLBACK,
                listOf(StagedEntry(source.path, staged.toString(), target.path, FileOperationStaging.STATE_TARGET_COMMITTED)),
            ),
        )

        FileOperationStaging.recoverOrphaned(root.toFile())

        assertTrue(source.exists())
        assertFalse(target.exists())
    }

    @Test
    fun interruptedOverwriteRestoresTheDestinationBackup() {
        val root = tempRoot()
        val target = root.resolve("target.txt").toFile().apply { writeText("old") }
        val operation = FileOperationStaging.createOperation(root.toFile(), "copy", StagingRecoveryPolicy.ROLLBACK)
        val staged = operation.toPath().resolve("payload/source.txt")
        val backup = operation.toPath().resolve("backup/target.txt")
        Files.createDirectories(staged.parent)
        Files.createDirectories(backup.parent)
        staged.toFile().writeText("new")
        FileOperationStaging.atomicMove(target.toPath(), backup, replaceExisting = false)
        FileOperationStaging.writeManifest(
            operation,
            StagingManifest(
                "copy",
                StagingRecoveryPolicy.ROLLBACK,
                listOf(
                    StagedEntry("/source.txt", staged.toString(), target.path, FileOperationStaging.STATE_TARGET_COMMITTED),
                    StagedEntry(target.path, backup.toString(), state = FileOperationStaging.STATE_BACKUP_MOVED),
                ),
            ),
        )
        target.writeText("new")

        FileOperationStaging.recoverOrphaned(root.toFile())

        assertEquals("old", target.readText())
    }

    private fun tempRoot() = Files.createTempDirectory("fileexplorer-staging").also(roots::add)
}
