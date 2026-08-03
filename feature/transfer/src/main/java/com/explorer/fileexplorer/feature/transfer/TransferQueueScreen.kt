package com.explorer.fileexplorer.feature.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TransferQueueViewModel @Inject constructor(
    private val manager: TransferQueueManager,
) : ViewModel() {
    val tasks = manager.tasks

    fun pause(id: Long) = manager.pause(id)
    fun resume(id: Long) = manager.resume(id)
    fun cancel(id: Long) = manager.cancel(id)
    fun move(id: Long, offset: Int) = manager.move(id, offset)
    fun setBandwidthLimit(id: Long, bytesPerSecond: Long) = manager.setBandwidthLimit(id, bytesPerSecond)
    fun resolveConflict(id: Long, action: TransferConflictAction, applyToAll: Boolean) =
        manager.resolveConflict(id, action, applyToAll)

    fun clearFinished() = manager.clearFinished()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TransferQueueViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var bandwidthTask by remember { mutableStateOf<TransferQueueTask?>(null) }
    val conflictTask = tasks.firstOrNull { it.state == TransferQueueState.WAITING_CONFLICT && it.conflict != null }

    BackHandler(onBack = onNavigateBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer Queue") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::clearFinished,
                        enabled = tasks.any { it.isTerminal },
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear finished")
                    }
                },
            )
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("No transfers queued", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TransferQueueTaskCard(
                        task = task,
                        canMoveUp = tasks.indexOf(task) > 0,
                        canMoveDown = tasks.indexOf(task) < tasks.lastIndex,
                        onPause = { viewModel.pause(task.id) },
                        onResume = { viewModel.resume(task.id) },
                        onCancel = { viewModel.cancel(task.id) },
                        onMoveUp = { viewModel.move(task.id, -1) },
                        onMoveDown = { viewModel.move(task.id, 1) },
                        onEditLimit = { bandwidthTask = task },
                    )
                }
            }
        }
    }

    bandwidthTask?.let { task ->
        BandwidthLimitDialog(
            task = task,
            onConfirm = { kbps ->
                viewModel.setBandwidthLimit(task.id, kbps * 1024L)
                bandwidthTask = null
            },
            onDismiss = { bandwidthTask = null },
        )
    }
    conflictTask?.let { task ->
        val conflict = task.conflict ?: return@let
        ConflictResolutionDialog(
            conflict = conflict,
            onResolve = { action, applyToAll -> viewModel.resolveConflict(task.id, action, applyToAll) },
        )
    }
}

@Composable
private fun TransferQueueTaskCard(
    task: TransferQueueTask,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEditLimit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${task.operation.name.lowercase().replaceFirstChar { it.uppercase() }} · ${task.sourcePaths.size} item(s)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(task.state.displayName(), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = "Destination: ${task.destination.ifBlank { "Same storage" }}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.totalBytes > 0) {
                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatBytes(task.transferredBytes)} / ${formatBytes(task.totalBytes)} · ${task.completedSources}/${task.sourcePaths.size} complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.currentFile.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            task.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.state == TransferQueueState.PAUSED) {
                    IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, "Resume") }
                } else if (task.state == TransferQueueState.QUEUED || task.state == TransferQueueState.RUNNING) {
                    IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, "Pause") }
                }
                if (!task.isTerminal) IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
                IconButton(onClick = onMoveUp, enabled = canMoveUp && task.state == TransferQueueState.QUEUED) {
                    Icon(Icons.Filled.ArrowUpward, "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown && task.state == TransferQueueState.QUEUED) {
                    Icon(Icons.Filled.ArrowDownward, "Move down")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEditLimit, enabled = !task.isTerminal) {
                    Icon(Icons.Filled.Speed, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (task.bandwidthLimitBytesPerSecond > 0) "${task.bandwidthLimitBytesPerSecond / 1024} KB/s" else "Unlimited")
                }
            }
        }
    }
}

@Composable
private fun BandwidthLimitDialog(
    task: TransferQueueTask,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(task.id, task.bandwidthLimitBytesPerSecond) {
        mutableStateOf(if (task.bandwidthLimitBytesPerSecond > 0) (task.bandwidthLimitBytesPerSecond / 1024).toString() else "")
    }
    val limit = value.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bandwidth limit") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("KB/s (blank for unlimited)") },
                singleLine = true,
                supportingText = { Text("Applies to this queued task") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(limit ?: 0L) }, enabled = value.isBlank() || limit != null && limit >= 0) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConflictResolutionDialog(
    conflict: TransferConflict,
    onResolve: (TransferConflictAction, Boolean) -> Unit,
) {
    var applyToAll by remember(conflict) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("File conflict") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Incoming: ${conflict.sourcePath}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Existing: ${conflict.destinationPath}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (conflict.isText && conflict.diffPreview.isNotBlank()) {
                    HorizontalDivider()
                    SelectionContainer {
                        Text(
                            conflict.diffPreview,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Apply this choice to all conflicts")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onResolve(TransferConflictAction.SKIP, applyToAll) }) { Text("Skip") }
                TextButton(onClick = { onResolve(TransferConflictAction.REPLACE, applyToAll) }) { Text("Replace") }
                TextButton(onClick = { onResolve(TransferConflictAction.RENAME, applyToAll) }) { Text("Rename") }
                TextButton(onClick = { onResolve(TransferConflictAction.KEEP_BOTH, applyToAll) }) { Text("Keep both") }
            }
        },
    )
}

private fun TransferQueueState.displayName(): String = when (this) {
    TransferQueueState.QUEUED -> "Queued"
    TransferQueueState.RUNNING -> "Running"
    TransferQueueState.PAUSED -> "Paused"
    TransferQueueState.WAITING_CONFLICT -> "Needs decision"
    TransferQueueState.COMPLETED -> "Complete"
    TransferQueueState.FAILED -> "Failed"
    TransferQueueState.CANCELLED -> "Cancelled"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
