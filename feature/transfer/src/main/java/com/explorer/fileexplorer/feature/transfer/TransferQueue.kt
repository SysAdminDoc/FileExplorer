package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.model.FileOperation

enum class TransferQueueState {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_CONFLICT,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class TransferConflictAction {
    SKIP,
    REPLACE,
    RENAME,
    KEEP_BOTH,
}

data class TransferConflict(
    val sourcePath: String,
    val destinationPath: String,
    val isText: Boolean,
    val diffPreview: String = "",
)

data class TransferQueueTask(
    val id: Long,
    val operation: FileOperation,
    val sourcePaths: List<String>,
    val destination: String,
    val bandwidthLimitBytesPerSecond: Long = 0L,
    val conflictAction: TransferConflictAction? = null,
    val applyConflictToAll: Boolean = false,
    val state: TransferQueueState = TransferQueueState.QUEUED,
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val completedSources: Int = 0,
    val currentFile: String = "",
    val error: String? = null,
    val conflict: TransferConflict? = null,
    val idempotencyKey: String = "transfer-$id",
    val retryCount: Int = 0,
) {
    val progress: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isTerminal: Boolean
        get() = state in setOf(TransferQueueState.COMPLETED, TransferQueueState.FAILED, TransferQueueState.CANCELLED)
}
