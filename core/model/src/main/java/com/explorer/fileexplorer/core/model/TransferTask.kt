package com.explorer.fileexplorer.core.model

data class TransferTask(
    val id: Long = System.currentTimeMillis(),
    val operation: FileOperation,
    val sources: List<FileItem>,
    val destination: String,
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val currentFile: String = "",
    val filesProcessed: Int = 0,
    val totalFiles: Int = 0,
    val state: TransferState = TransferState.PENDING,
) {
    val progress: Float
        get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f

    val speedBytesPerSec: Long = 0L

    val displayProgress: String
        get() = "%.1f%%".format(progress * 100)
}

enum class FileOperation {
    COPY, MOVE, DELETE, COMPRESS, EXTRACT
}

enum class TransferState {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

data class ClipboardContent(
    val items: List<FileItem> = emptyList(),
    val operation: FileOperation = FileOperation.COPY,
    val sourcePath: String = "",
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val isCut: Boolean get() = operation == FileOperation.MOVE
}

enum class ConflictResolution {
    OVERWRITE, SKIP, RENAME, ASK
}

data class StorageVolume(
    val name: String,
    val path: String,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val isRemovable: Boolean = false,
    val isPrimary: Boolean = false,
) {
    val usedBytes: Long get() = totalBytes - freeBytes
    val usagePercent: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

data class Bookmark(
    val id: Long = 0,
    val name: String,
    val path: String,
    val sortOrder: Int = 0,
)
