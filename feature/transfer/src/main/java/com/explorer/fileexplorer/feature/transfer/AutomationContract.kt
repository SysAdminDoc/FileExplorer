package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.model.ConflictResolution
import java.util.Locale

/** Stable external action and extra names for Tasker, Automate, and similar tools. */
object AutomationContract {
    const val ACTION_COPY = "com.explorer.fileexplorer.action.COPY"
    const val ACTION_MOVE = "com.explorer.fileexplorer.action.MOVE"
    const val ACTION_ZIP = "com.explorer.fileexplorer.action.ZIP"
    const val ACTION_UPLOAD = "com.explorer.fileexplorer.action.UPLOAD"
    const val ACTION_RESULT = "com.explorer.fileexplorer.action.TRANSFER_RESULT"

    const val EXTRA_SOURCE = "source"
    const val EXTRA_SOURCES = "sources"
    const val EXTRA_DESTINATION = "destination"
    const val EXTRA_FORMAT = "format"
    const val EXTRA_CONNECTION_ID = "connection_id"
    const val EXTRA_CONFLICT = "conflict"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_OPERATION = "operation"
    const val EXTRA_STATUS = "status"
    const val EXTRA_ERROR = "error"

    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    const val MAX_SOURCES = 256

    enum class Operation {
        COPY,
        MOVE,
        ZIP,
        UPLOAD,
    }

    data class Request(
        val operation: Operation,
        val sourcePaths: List<String>,
        val destination: String,
        val archiveFormat: ArchiveFormat = ArchiveFormat.ZIP,
        val conflictResolution: ConflictResolution = ConflictResolution.RENAME,
        val connectionId: Long? = null,
    )

    fun parse(
        action: String?,
        sourcePaths: List<String>,
        destination: String?,
        format: String? = null,
        connectionId: Long? = null,
        conflict: String? = null,
    ): Result<Request> = runCatching {
        val operation = when (action) {
            ACTION_COPY -> Operation.COPY
            ACTION_MOVE -> Operation.MOVE
            ACTION_ZIP -> Operation.ZIP
            ACTION_UPLOAD -> Operation.UPLOAD
            else -> error("Unsupported automation action: ${action.orEmpty()}")
        }
        val sources = sourcePaths
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        require(sources.isNotEmpty()) { "At least one source path is required" }
        require(sources.size <= MAX_SOURCES) { "At most $MAX_SOURCES source paths are supported" }
        require(sources.none { '\u0000' in it }) { "Source paths cannot contain NUL" }

        val target = destination?.trim().orEmpty()
        require(target.isNotEmpty()) { "A destination is required" }
        require('\u0000' !in target) { "The destination cannot contain NUL" }

        val archiveFormat = if (operation == Operation.ZIP) parseArchiveFormat(format) else ArchiveFormat.ZIP
        val conflictResolution = parseConflict(conflict)
        val uploadConnectionId = if (operation == Operation.UPLOAD) {
            require(connectionId != null && connectionId > 0) {
                "Upload actions require a positive saved connection_id"
            }
            connectionId
        } else {
            null
        }

        Request(
            operation = operation,
            sourcePaths = sources,
            destination = target,
            archiveFormat = archiveFormat,
            conflictResolution = conflictResolution,
            connectionId = uploadConnectionId,
        )
    }

    fun parseArchiveFormat(value: String?): ArchiveFormat {
        return when (value?.trim()?.lowercase(Locale.ROOT)?.removePrefix(".")) {
            null, "", "zip" -> ArchiveFormat.ZIP
            "7z" -> ArchiveFormat.SEVEN_Z
            "tar.gz", "tgz" -> ArchiveFormat.TAR_GZ
            else -> error("Unsupported archive format: $value")
        }
    }

    private fun parseConflict(value: String?): ConflictResolution {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "rename", "keep-both" -> ConflictResolution.RENAME
            "overwrite", "replace" -> ConflictResolution.OVERWRITE
            "skip" -> ConflictResolution.SKIP
            "ask" -> error("Interactive conflict resolution is unavailable for automation")
            else -> error("Unsupported conflict mode: $value")
        }
    }
}
