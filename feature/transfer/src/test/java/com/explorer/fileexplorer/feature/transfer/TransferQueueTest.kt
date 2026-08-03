package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.model.FileOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferQueueTest {

    @Test
    fun progressIsBoundedAndEmptyTransfersRemainAtZero() {
        val overReported = task(totalBytes = 100L, transferredBytes = 150L)
        val empty = task(totalBytes = 0L, transferredBytes = 0L)

        assertEquals(1f, overReported.progress)
        assertEquals(0f, empty.progress)
    }

    @Test
    fun onlyTerminalStatesAreMarkedTerminal() {
        assertTrue(task(state = TransferQueueState.COMPLETED).isTerminal)
        assertTrue(task(state = TransferQueueState.FAILED).isTerminal)
        assertTrue(task(state = TransferQueueState.CANCELLED).isTerminal)
        assertFalse(task(state = TransferQueueState.QUEUED).isTerminal)
        assertFalse(task(state = TransferQueueState.WAITING_CONFLICT).isTerminal)
    }

    @Test
    fun conflictCarriesActionChoicesAndDiffPreview() {
        val conflict = TransferConflict(
            sourcePath = "/incoming/readme.txt",
            destinationPath = "/existing/readme.txt",
            isText = true,
            diffPreview = "- old\n+new",
        )
        val task = task(state = TransferQueueState.WAITING_CONFLICT, conflict = conflict)

        assertEquals(TransferConflictAction.entries.toSet(), setOf(
            TransferConflictAction.SKIP,
            TransferConflictAction.REPLACE,
            TransferConflictAction.RENAME,
            TransferConflictAction.KEEP_BOTH,
        ))
        assertEquals("- old\n+new", task.conflict?.diffPreview)
        assertFalse(task.isTerminal)
    }

    private fun task(
        state: TransferQueueState = TransferQueueState.QUEUED,
        totalBytes: Long = 100L,
        transferredBytes: Long = 0L,
        conflict: TransferConflict? = null,
    ) = TransferQueueTask(
        id = 1L,
        operation = FileOperation.COPY,
        sourcePaths = listOf("/incoming/readme.txt"),
        destination = "/existing",
        state = state,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        conflict = conflict,
    )
}
