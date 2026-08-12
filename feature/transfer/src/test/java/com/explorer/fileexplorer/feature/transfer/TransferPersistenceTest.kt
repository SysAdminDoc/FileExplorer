package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.database.TransferTaskEntity
import com.explorer.fileexplorer.core.model.FileOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferPersistenceTest {

    @Test
    fun pathCodecRoundTripsOpaquePaths() {
        val paths = listOf(
            "content://com.example.documents/tree/primary%3AProjects/document/primary%3AProjects%2Freport|draft.txt",
            "C:\\Users\\Ada\\notes\n日記.txt",
            "sftp://host/a folder/emoji-😀.bin",
        )

        val encoded = TransferPathCodec.encode(paths)

        assertFalse(paths.any(encoded::contains))
        assertEquals(paths, TransferPathCodec.decode(encoded))
    }

    @Test
    fun taskEntityRoundTripPreservesRecoveryState() {
        val original = task(
            id = 42L,
            state = TransferQueueState.WAITING_CONFLICT,
            totalBytes = 900L,
            transferredBytes = 300L,
            completedSources = 1,
            conflictAction = TransferConflictAction.REPLACE,
            applyConflictToAll = true,
            conflict = TransferConflict(
                sourcePath = "/source/a.txt",
                destinationPath = "/destination/a.txt",
                isText = true,
                diffPreview = "- old\n+ new",
                sourceSize = 12L,
                destinationSize = 18L,
                sourceModified = 100L,
                destinationModified = 200L,
                plannedKeepBothPath = "/destination/a (copy-abc123).txt",
            ),
            conflictDecisions = mapOf(
                "/source/a.txt" to TransferConflictAction.KEEP_BOTH,
                "/source/b.txt" to TransferConflictAction.SKIP,
            ),
            intendedEntries = listOf(
                TransferJournalEntry("/source/a.txt", "/destination/a.txt"),
                TransferJournalEntry("/source/b.txt", "/destination/b.txt"),
            ),
            committedEntries = listOf(
                TransferJournalEntry("/source/a.txt", "/destination/a.txt", TransferJournalState.COMMITTED),
            ),
        )

        val restored = original.toEntity(queueOrder = 3).toTask()

        assertEquals(original, restored)
        assertEquals("transfer-42", restored.idempotencyKey)
        assertEquals(original.intendedEntries, restored.intendedEntries)
        assertEquals(original.committedEntries, restored.committedEntries)
        assertEquals(original.conflictDecisions, restored.conflictDecisions)
    }

    @Test
    fun corruptEntitiesAreRejectedBeforeTheyReachTheQueue() {
        val valid = task(id = 7L).toEntity(queueOrder = 0)

        assertFailsWith<IllegalArgumentException> {
            valid.copy(sourcePaths = "not-base64|/").toTask()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(state = "UNKNOWN").toTask()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(completedSources = 3).toTask()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(bandwidthLimitBytesPerSecond = -1L).toTask()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(retryCount = -1).toTask()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(recoveryPolicy = "UNKNOWN").toTask()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(conflictSourcePath = "/source/a.txt").toTask()
        }
    }

    @Test
    fun processDeathRequeuesOnlyInterruptedWork() {
        val running = task(id = 1L, state = TransferQueueState.RUNNING, completedSources = 1)
        val waiting = task(id = 2L, state = TransferQueueState.WAITING_CONFLICT)
        val paused = task(id = 3L, state = TransferQueueState.PAUSED)
        val completed = task(id = 4L, state = TransferQueueState.COMPLETED)

        val recovered = listOf(running, waiting, paused, completed).map { it.recoverAfterProcessDeath() }

        assertEquals(TransferQueueState.QUEUED, recovered[0].state)
        assertEquals(1, recovered[0].completedSources)
        assertEquals(TRANSFER_RECOVERY_ERROR, recovered[0].error)
        assertEquals(null, recovered[1].conflict)
        assertEquals(TransferQueueState.PAUSED, recovered[2].state)
        assertEquals(TransferQueueState.COMPLETED, recovered[3].state)
        assertTrue(recovered[0].error?.contains("permissions") == true)
    }

    private fun task(
        id: Long = 1L,
        state: TransferQueueState = TransferQueueState.QUEUED,
        totalBytes: Long = 100L,
        transferredBytes: Long = 0L,
        completedSources: Int = 0,
        conflictAction: TransferConflictAction? = null,
        applyConflictToAll: Boolean = false,
        conflict: TransferConflict? = null,
        conflictDecisions: Map<String, TransferConflictAction> = emptyMap(),
        intendedEntries: List<TransferJournalEntry> = emptyList(),
        committedEntries: List<TransferJournalEntry> = emptyList(),
    ) = TransferQueueTask(
        id = id,
        idempotencyKey = "transfer-$id",
        operation = FileOperation.COPY,
        sourcePaths = listOf("/source/a.txt", "/source/b.txt"),
        destination = "/destination",
        bandwidthLimitBytesPerSecond = 2048L,
        conflictAction = conflictAction,
        applyConflictToAll = applyConflictToAll,
        state = state,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        completedSources = completedSources,
        retryCount = 2,
        currentFile = "/source/a.txt",
        error = null,
        conflict = conflict,
        conflictDecisions = conflictDecisions,
        intendedEntries = intendedEntries,
        committedEntries = committedEntries,
    )
}
