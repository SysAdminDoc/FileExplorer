package com.explorer.fileexplorer.feature.browser

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.DuplicateGroup
import com.explorer.fileexplorer.core.data.LocalTrashManager
import com.explorer.fileexplorer.core.data.StorageAnalyzer
import com.explorer.fileexplorer.core.data.StorageEntry
import com.explorer.fileexplorer.core.data.StorageScanPhase
import com.explorer.fileexplorer.core.data.StorageScanProgress
import com.explorer.fileexplorer.core.data.StorageScanResult
import com.explorer.fileexplorer.core.data.StorageTreeNode
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min
import javax.inject.Inject
import java.nio.file.Paths

data class StorageAnalyzerUiState(
    val rootPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val isScanning: Boolean = false,
    val progress: StorageScanProgress = StorageScanProgress(),
    val result: StorageScanResult? = null,
    val error: String? = null,
    val actionInProgress: Boolean = false,
    val lastActionMovedCount: Int? = null,
    val actionError: String? = null,
)

@HiltViewModel
class StorageAnalyzerViewModel @Inject constructor(
    private val analyzer: StorageAnalyzer,
    private val trashManager: LocalTrashManager,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageAnalyzerUiState())
    val state: StateFlow<StorageAnalyzerUiState> = _state.asStateFlow()
    private var scanJob: Job? = null

    fun scan(resume: Boolean = false) {
        scanJob?.cancel()
        val rootPath = _state.value.rootPath
        val checkpoint = if (resume) _state.value.result?.checkpoint else null
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isScanning = true, progress = StorageScanProgress(), error = null) }
            try {
                val result = analyzer.scan(
                    rootPath,
                    onProgress = { progress -> _state.update { it.copy(progress = progress) } },
                    resumeFrom = checkpoint,
                )
                _state.update { it.copy(isScanning = false, result = result) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isScanning = false, error = e.message ?: "Unable to analyze storage") }
            }
        }
    }

    fun moveDuplicatesToTrash(paths: Set<String>) {
        if (paths.isEmpty() || _state.value.actionInProgress) return
        val root = Paths.get(_state.value.rootPath).toAbsolutePath().normalize()
        val safePaths = paths.mapNotNull { path ->
            runCatching { Paths.get(path).toAbsolutePath().normalize() }
                .getOrNull()
                ?.takeIf { it != root && it.startsWith(root) }
                ?.toString()
        }
        if (safePaths.size != paths.size) {
            _state.update { it.copy(actionError = "Only files inside the analyzed root can be reviewed") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(actionInProgress = true, actionError = null, lastActionMovedCount = null) }
            val result = trashManager.moveToTrash(safePaths, listOf(root.toString()))
            result.fold(
                onSuccess = { count ->
                    _state.update { it.copy(actionInProgress = false, lastActionMovedCount = count) }
                    scan()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(actionInProgress = false, actionError = error.message ?: "Unable to move duplicates to Trash")
                    }
                },
            )
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(isScanning = false) }
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}

private enum class AnalyzerSection { TREEMAP, DUPLICATES, LARGEST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: StorageAnalyzerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(AnalyzerSection.TREEMAP) }
    var nodeStack by remember(state.result) { mutableStateOf(state.result?.let { listOf(it.root) } ?: emptyList()) }
    var duplicateSelection by remember(state.result) { mutableStateOf(emptySet<String>()) }

    BackHandler(enabled = nodeStack.size > 1) {
        nodeStack = nodeStack.dropLast(1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(DesignSystemR.string.storage_analyzer)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(DesignSystemR.string.back))
                    }
                },
                actions = {
                    if (state.isScanning) {
                        IconButton(onClick = viewModel::cancelScan) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(DesignSystemR.string.cancel_scan))
                        }
                    } else {
                        IconButton(onClick = viewModel::scan) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(DesignSystemR.string.rescan_storage))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            if (state.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = if (state.progress.phase == StorageScanPhase.HASHING) {
                        stringResource(
                            DesignSystemR.string.hashing_progress,
                            state.progress.files,
                            state.progress.hashBytesRead / 1024,
                        )
                    } else {
                        stringResource(DesignSystemR.string.scanning_progress, state.progress.files, state.progress.directories)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            state.lastActionMovedCount?.let { count ->
                Text(
                    text = stringResource(DesignSystemR.string.duplicates_moved_to_trash, count),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            state.actionError?.let { error ->
                Text(
                    text = stringResource(DesignSystemR.string.duplicate_action_failed, error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            val result = state.result
            if (result == null) {
                AnalyzerEmptyState(isScanning = state.isScanning, onScan = viewModel::scan)
            } else {
                        AnalyzerSummary(result)
                        if (!result.isComplete || !result.hashAnalysisComplete) {
                            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(DesignSystemR.string.analysis_incomplete), style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        stringResource(DesignSystemR.string.analysis_skipped_files, result.skippedFiles),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedButton(onClick = { viewModel.scan(resume = true) }, enabled = !state.isScanning) {
                                        Text(stringResource(DesignSystemR.string.resume_analysis))
                                    }
                                }
                            }
                        }
                Spacer(Modifier.height(12.dp))
                AnalyzerSectionSelector(section = section, onSelect = { section = it })
                Spacer(Modifier.height(8.dp))
                when (section) {
                    AnalyzerSection.TREEMAP -> {
                        val current = nodeStack.lastOrNull() ?: result.root
                        AnalyzerNodeBreadcrumb(
                            stack = nodeStack.ifEmpty { listOf(result.root) },
                            onUp = { if (nodeStack.size > 1) nodeStack = nodeStack.dropLast(1) },
                        )
                        StorageTreemap(
                            nodes = current.children,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            onNodeClick = { node ->
                                if (!node.isAggregate && node.children.isNotEmpty()) nodeStack = nodeStack + node
                            },
                        )
                        Text(
                            text = if (current.children.isEmpty()) stringResource(DesignSystemR.string.no_child_entries)
                            else stringResource(DesignSystemR.string.treemap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    AnalyzerSection.DUPLICATES -> DuplicateList(
                        groups = result.duplicateGroups,
                        selectedPaths = duplicateSelection,
                        actionInProgress = state.actionInProgress,
                        onToggle = { path, group ->
                            val selectedInGroup = group.files.count { it.path in duplicateSelection }
                            duplicateSelection = if (path in duplicateSelection) {
                                duplicateSelection - path
                            } else if (selectedInGroup < group.files.size - 1) {
                                duplicateSelection + path
                            } else {
                                duplicateSelection
                            }
                        },
                        onMoveToTrash = {
                            viewModel.moveDuplicatesToTrash(duplicateSelection)
                            duplicateSelection = emptySet()
                        },
                    )
                    AnalyzerSection.LARGEST -> LargestFileList(result.largestFiles)
                }
            }
        }
    }
}

@Composable
private fun AnalyzerEmptyState(isScanning: Boolean, onScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Analytics, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(DesignSystemR.string.analyze_storage_usage), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(DesignSystemR.string.analyzer_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onScan, enabled = !isScanning) { Text(stringResource(DesignSystemR.string.scan_storage)) }
    }
}

@Composable
private fun AnalyzerSummary(result: StorageScanResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AnalyzerMetric(stringResource(DesignSystemR.string.used), formatBytes(result.totalBytes), Modifier.weight(1f))
        AnalyzerMetric(stringResource(DesignSystemR.string.files), result.fileCount.toString(), Modifier.weight(1f))
        AnalyzerMetric(stringResource(DesignSystemR.string.folders), result.directoryCount.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun AnalyzerMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnalyzerSectionSelector(section: AnalyzerSection, onSelect: (AnalyzerSection) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AnalyzerSection.entries.forEach { candidate ->
            FilterChip(
                selected = section == candidate,
                onClick = { onSelect(candidate) },
                label = { Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@Composable
private fun AnalyzerNodeBreadcrumb(stack: List<StorageTreeNode>, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            stack.joinToString(" / ") { it.name },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (stack.size > 1) TextButton(onClick = onUp) { Text(stringResource(DesignSystemR.string.up)) }
    }
}

@Composable
private fun StorageTreemap(
    nodes: List<StorageTreeNode>,
    modifier: Modifier = Modifier,
    onNodeClick: (StorageTreeNode) -> Unit,
) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    Layout(
        content = {
            nodes.forEachIndexed { index, node ->
                val clickable = node.children.isNotEmpty() && !node.isAggregate
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette[index % palette.size])
                        .clickable(enabled = clickable) { onNodeClick(node) }
                        .padding(8.dp),
                ) {
                    Column {
                        Text(node.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(node.size), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val rects = sliceRects(nodes, width, height)
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = rects.getOrElse(index) { TreemapRect(0, 0, width, height) }
            measurable.measure(Constraints.fixed(rect.width.coerceAtLeast(1), rect.height.coerceAtLeast(1)))
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val rect = rects.getOrElse(index) { TreemapRect(0, 0, width, height) }
                placeable.place(rect.left, rect.top)
            }
        }
    }
}

private data class TreemapRect(val left: Int, val top: Int, val width: Int, val height: Int)

private fun sliceRects(nodes: List<StorageTreeNode>, width: Int, height: Int): List<TreemapRect> {
    if (nodes.isEmpty() || width <= 0 || height <= 0) return emptyList()
    val total = nodes.sumOf { it.size }.coerceAtLeast(1L)
    val horizontal = width >= height
    var offset = 0
    return nodes.mapIndexed { index, node ->
        val remaining = if (horizontal) width - offset else height - offset
        val size = if (index == nodes.lastIndex) remaining
        else min(remaining, ((if (horizontal) width else height) * node.size / total).toInt().coerceAtLeast(1))
        val rect = if (horizontal) TreemapRect(offset, 0, size, height) else TreemapRect(0, offset, width, size)
        offset += size
        rect
    }
}

@Composable
private fun DuplicateList(
    groups: List<DuplicateGroup>,
    selectedPaths: Set<String>,
    actionInProgress: Boolean,
    onToggle: (String, DuplicateGroup) -> Unit,
    onMoveToTrash: () -> Unit,
) {
    if (groups.isEmpty()) {
        AnalyzerMessage(stringResource(DesignSystemR.string.no_duplicate_files))
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedPaths.isNotEmpty()) {
            Button(
                onClick = onMoveToTrash,
                enabled = !actionInProgress,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(stringResource(DesignSystemR.string.move_selected_duplicates_to_trash, selectedPaths.size))
            }
        }
        Text(
            stringResource(DesignSystemR.string.duplicate_keep_one),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { group -> "${group.size}:${group.files.firstOrNull()?.path}" }) { group ->
                DuplicateCard(group, selectedPaths, onToggle)
            }
        }
    }
}

@Composable
private fun DuplicateCard(
    group: DuplicateGroup,
    selectedPaths: Set<String>,
    onToggle: (String, DuplicateGroup) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(DesignSystemR.string.duplicate_copies, group.files.size, formatBytes(group.size)),
                style = MaterialTheme.typography.titleSmall,
            )
            group.files.forEach { file ->
                val selectedCount = group.files.count { it.path in selectedPaths }
                val checked = file.path in selectedPaths
                val enabled = checked || selectedCount < group.files.size - 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onToggle(file.path, group) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle(file.path, group) },
                        enabled = enabled,
                    )
                    Text(file.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun LargestFileList(files: List<StorageEntry>) {
    if (files.isEmpty()) {
        AnalyzerMessage(stringResource(DesignSystemR.string.no_files_found))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            ListItem(
                headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text(formatBytes(file.size), style = MaterialTheme.typography.labelMedium) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun AnalyzerMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
