package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.database.TransferTaskEntity
import com.explorer.fileexplorer.core.model.FileOperation
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object TransferPathCodec {
    fun encode(paths: List<String>): String = paths.joinToString("|") { path ->
        Base64.getEncoder().encodeToString(path.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(encoded: String): List<String> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split('|').map { value ->
            require(value.isNotEmpty()) { "Invalid persisted transfer path" }
            String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        }
    }
}

private object TransferJournalCodec {
    fun encode(entries: List<TransferJournalEntry>): String = entries.joinToString("\n") { entry ->
        listOf(
            Base64.getEncoder().encodeToString(entry.sourcePath.toByteArray(StandardCharsets.UTF_8)),
            Base64.getEncoder().encodeToString(entry.destinationPath.toByteArray(StandardCharsets.UTF_8)),
            entry.state.name,
        ).joinToString("|")
    }

    fun decode(encoded: String): List<TransferJournalEntry> {
        if (encoded.isBlank()) return emptyList()
        return encoded.lineSequence().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('|')
            require(fields.size == 3) { "Invalid persisted transfer journal entry" }
            TransferJournalEntry(
                sourcePath = String(Base64.getDecoder().decode(fields[0]), StandardCharsets.UTF_8),
                destinationPath = String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8),
                state = runCatching { TransferJournalState.valueOf(fields[2]) }
                    .getOrElse { throw IllegalArgumentException("Invalid persisted transfer journal state", it) },
            )
        }.toList()
    }
}

internal fun TransferQueueTask.toEntity(queueOrder: Int): TransferTaskEntity = TransferTaskEntity(
    id = id,
    idempotencyKey = idempotencyKey.ifBlank { "transfer-$id" },
    queueOrder = queueOrder,
    operation = operation.name,
    sourcePaths = TransferPathCodec.encode(sourcePaths),
    destination = destination,
    bandwidthLimitBytesPerSecond = bandwidthLimitBytesPerSecond,
    conflictAction = conflictAction?.name,
    applyConflictToAll = applyConflictToAll,
    state = state.name,
    totalBytes = totalBytes,
    transferredBytes = transferredBytes,
    completedSources = completedSources,
    retryCount = retryCount,
    currentFile = currentFile,
    error = error,
    conflictSourcePath = conflict?.sourcePath,
    conflictDestinationPath = conflict?.destinationPath,
    conflictIsText = conflict?.isText ?: false,
    conflictDiffPreview = conflict?.diffPreview ?: "",
    intendedEntries = TransferJournalCodec.encode(intendedEntries),
    committedEntries = TransferJournalCodec.encode(committedEntries),
    recoveryPolicy = recoveryPolicy.name,
    updatedAt = System.currentTimeMillis(),
)

internal const val TRANSFER_RECOVERY_ERROR =
    "Recovered after process death; permissions and conflicts will be rechecked"

internal fun TransferQueueTask.recoverAfterProcessDeath(): TransferQueueTask =
    if (state == TransferQueueState.RUNNING || state == TransferQueueState.WAITING_CONFLICT) {
        copy(
            state = TransferQueueState.QUEUED,
            error = if (committedEntries.size < intendedEntries.size) {
                "$TRANSFER_RECOVERY_ERROR; partial journal: ${committedEntries.size}/${intendedEntries.size} committed"
            } else {
                TRANSFER_RECOVERY_ERROR
            },
            conflict = null,
        )
    } else {
        this
    }

internal fun TransferTaskEntity.toTask(): TransferQueueTask {
    val parsedOperation = runCatching { FileOperation.valueOf(operation) }
        .getOrElse { throw IllegalArgumentException("Invalid persisted transfer operation: $operation", it) }
    val parsedState = runCatching { TransferQueueState.valueOf(state) }
        .getOrElse { throw IllegalArgumentException("Invalid persisted transfer state: $state", it) }
    val parsedConflictAction = conflictAction?.let { value ->
        runCatching { TransferConflictAction.valueOf(value) }
            .getOrElse { throw IllegalArgumentException("Invalid persisted transfer conflict action: $value", it) }
    }
    val parsedPaths = TransferPathCodec.decode(sourcePaths)
    require(parsedPaths.isNotEmpty()) { "Persisted transfer has no sources" }
    require(id > 0L) { "Persisted transfer has invalid id" }
    require(idempotencyKey.isNotBlank()) { "Persisted transfer has no idempotency key" }
    require(queueOrder >= 0) { "Persisted transfer has invalid queue order" }
    require(bandwidthLimitBytesPerSecond >= 0L) { "Persisted transfer has invalid bandwidth limit" }
    require(totalBytes >= 0 && transferredBytes >= 0) { "Persisted transfer has invalid byte counts" }
    require(completedSources in 0..parsedPaths.size) { "Persisted transfer has invalid source checkpoint" }
    require(retryCount >= 0) { "Persisted transfer has invalid retry count" }
    val parsedIntendedEntries = TransferJournalCodec.decode(intendedEntries)
    val parsedCommittedEntries = TransferJournalCodec.decode(committedEntries)
    require(parsedCommittedEntries.all { it.state != TransferJournalState.PLANNED }) {
        "Persisted transfer has uncommitted entries in the committed journal"
    }
    require(parsedCommittedEntries.all { entry -> parsedIntendedEntries.any { it.sourcePath == entry.sourcePath && it.destinationPath == entry.destinationPath } }) {
        "Persisted transfer committed journal is not a subset of the intended journal"
    }
    val parsedRecoveryPolicy = runCatching { TransferRecoveryPolicy.valueOf(recoveryPolicy) }
        .getOrElse { throw IllegalArgumentException("Invalid persisted transfer recovery policy", it) }
    val savedConflictSource = conflictSourcePath
    val savedConflictDestination = conflictDestinationPath
    val parsedConflict = if (savedConflictSource != null || savedConflictDestination != null) {
        require(!savedConflictSource.isNullOrBlank() && !savedConflictDestination.isNullOrBlank()) {
            "Persisted transfer has incomplete conflict data"
        }
        TransferConflict(
            sourcePath = savedConflictSource!!,
            destinationPath = savedConflictDestination!!,
            isText = conflictIsText,
            diffPreview = conflictDiffPreview,
        )
    } else {
        null
    }
    return TransferQueueTask(
        id = id,
        idempotencyKey = idempotencyKey,
        operation = parsedOperation,
        sourcePaths = parsedPaths,
        destination = destination,
        bandwidthLimitBytesPerSecond = bandwidthLimitBytesPerSecond.coerceAtLeast(0L),
        conflictAction = parsedConflictAction,
        applyConflictToAll = applyConflictToAll,
        state = parsedState,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        completedSources = completedSources,
        retryCount = retryCount,
        currentFile = currentFile,
        error = error,
        conflict = parsedConflict,
        intendedEntries = parsedIntendedEntries,
        committedEntries = parsedCommittedEntries,
        recoveryPolicy = parsedRecoveryPolicy,
    )
}
