package com.explorer.fileexplorer.feature.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.ui.BreadcrumbBar
import com.explorer.fileexplorer.core.ui.FileListItem

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DualPaneContent(
    state: BrowserUiState,
    onPrimaryNavigate: (String) -> Unit,
    onPrimaryItemClick: (FileItem) -> Unit,
    onPrimaryItemLongClick: (FileItem) -> Unit,
    onSecondaryNavigate: (String) -> Unit,
    onSecondaryItemClick: (FileItem) -> Unit,
    onSecondaryItemLongClick: (FileItem) -> Unit,
    onPrimaryNavigateUp: () -> Unit,
    onSecondaryNavigateUp: () -> Unit,
    onPrimaryRefresh: () -> Unit,
    onSecondaryRefresh: () -> Unit,
    onRequestDrop: (List<FileItem>, String, BrowserPane, BrowserPane) -> Unit,
) {
    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var primaryBounds by remember { mutableStateOf(Rect.Zero) }
    var secondaryBounds by remember { mutableStateOf(Rect.Zero) }
    val itemBounds = remember { mutableStateMapOf<ItemKey, Rect>() }

    fun beginDrag(pane: BrowserPane, item: FileItem, localOffset: Offset) {
        val selected = if (pane == BrowserPane.PRIMARY) state.selectedItems else state.secondarySelectedItems
        val files = if (pane == BrowserPane.PRIMARY) state.files else state.secondaryFiles
        val items = if (item.path in selected) files.filter { it.path in selected } else listOf(item)
        val itemBoundsInRoot = itemBounds[ItemKey(pane, item.path)]
        val position = itemBoundsInRoot?.topLeft?.plus(localOffset) ?: Offset.Zero
        dragSession = DragSession(pane, items, position)
    }

    fun updateDrag(delta: Offset) {
        dragSession = dragSession?.let { it.copy(position = it.position + delta) }
    }

    fun finishDrag() {
        val session = dragSession ?: return
        val targetPane = when {
            primaryBounds.contains(session.position) -> BrowserPane.PRIMARY
            secondaryBounds.contains(session.position) -> BrowserPane.SECONDARY
            else -> null
        }
        if (targetPane != null && targetPane != session.sourcePane) {
            val destination = if (targetPane == BrowserPane.PRIMARY) state.currentPath else state.secondaryPath
            onRequestDrop(session.items, destination, session.sourcePane, targetPane)
        }
        dragSession = null
    }

    Row(modifier = Modifier.fillMaxSize()) {
        PanePanel(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { primaryBounds = it.boundsInRoot() },
            title = "Left pane",
            path = state.currentPath,
            files = state.files,
            isLoading = state.isLoading,
            error = state.error,
            selectedItems = state.selectedItems,
            compactDensity = state.compactDensity,
            isDropTarget = dragSession?.let {
                it.sourcePane != BrowserPane.PRIMARY && primaryBounds.contains(it.position)
            } == true,
            itemBounds = itemBounds,
            pane = BrowserPane.PRIMARY,
            onNavigate = onPrimaryNavigate,
            onItemClick = onPrimaryItemClick,
            onItemLongClick = onPrimaryItemLongClick,
            onNavigateUp = onPrimaryNavigateUp,
            onRefresh = onPrimaryRefresh,
            onDragStart = ::beginDrag,
            onDrag = ::updateDrag,
            onDragEnd = ::finishDrag,
            onDragCancel = { dragSession = null },
        )

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        PanePanel(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { secondaryBounds = it.boundsInRoot() },
            title = "Right pane",
            path = state.secondaryPath,
            files = state.secondaryFiles,
            isLoading = state.secondaryIsLoading,
            error = state.secondaryError,
            selectedItems = state.secondarySelectedItems,
            compactDensity = state.compactDensity,
            isDropTarget = dragSession?.let {
                it.sourcePane != BrowserPane.SECONDARY && secondaryBounds.contains(it.position)
            } == true,
            itemBounds = itemBounds,
            pane = BrowserPane.SECONDARY,
            onNavigate = onSecondaryNavigate,
            onItemClick = onSecondaryItemClick,
            onItemLongClick = onSecondaryItemLongClick,
            onNavigateUp = onSecondaryNavigateUp,
            onRefresh = onSecondaryRefresh,
            onDragStart = ::beginDrag,
            onDrag = ::updateDrag,
            onDragEnd = ::finishDrag,
            onDragCancel = { dragSession = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PanePanel(
    modifier: Modifier,
    title: String,
    path: String,
    files: List<FileItem>,
    isLoading: Boolean,
    error: String?,
    selectedItems: Set<String>,
    compactDensity: Boolean,
    isDropTarget: Boolean,
    itemBounds: SnapshotStateMap<ItemKey, Rect>,
    pane: BrowserPane,
    onNavigate: (String) -> Unit,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onDragStart: (BrowserPane, FileItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val borderColor = if (isDropTarget) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .fillMaxHeight()
            .border(if (isDropTarget) 2.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one folder")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh pane")
            }
        }

        BreadcrumbBar(currentPath = path, onNavigate = onNavigate)

        if (isDropTarget) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Release to transfer here", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (files.isEmpty() && !isLoading) {
                PaneEmptyState(error)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = files, key = { it.path }) { item ->
                        val itemKey = ItemKey(pane, item.path)
                        FileListItem(
                            item = item,
                            isSelected = item.path in selectedItems,
                            selectionMode = selectedItems.isNotEmpty(),
                            compact = compactDensity,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongClick(item) },
                            modifier = Modifier
                                .onGloballyPositioned { itemBounds[itemKey] = it.boundsInRoot() }
                                .pointerInput(itemKey) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            currentOnDragStart(pane, item, offset)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            currentOnDrag(amount)
                                        },
                                        onDragEnd = { currentOnDragEnd() },
                                        onDragCancel = { currentOnDragCancel() },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneEmptyState(error: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(
            text = error ?: "Empty folder",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DropConfirmationDialog(
    request: PendingDrop,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
        title = { Text("Transfer files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (request.items.size == 1) request.items.first().name
                    else "${request.items.size} selected items",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Destination: ${request.destinationPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("Copy") }
                Button(onClick = onMove) { Text("Move") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class ItemKey(val pane: BrowserPane, val path: String)

private data class DragSession(
    val sourcePane: BrowserPane,
    val items: List<FileItem>,
    val position: Offset,
)
