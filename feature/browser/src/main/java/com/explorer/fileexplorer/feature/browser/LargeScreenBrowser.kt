package com.explorer.fileexplorer.feature.browser

import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.StorageVolume
import com.explorer.fileexplorer.core.storage.RootState
import com.explorer.fileexplorer.core.ui.BreadcrumbBar
import com.explorer.fileexplorer.core.ui.FileGridItem
import com.explorer.fileexplorer.core.ui.FileListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LargeScreenBrowserContent(
    state: BrowserUiState,
    onNavigate: (String) -> Unit,
    onOpenItem: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShowProperties: (FileItem) -> Unit,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddTab: () -> Unit,
    onReorderTabs: (Int, Int) -> Unit,
) {
    var previewItem by remember { mutableStateOf<FileItem?>(null) }
    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state.currentPath) { previewItem = null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.isCtrlPressed && event.key == Key.A -> {
                        onSelectAll()
                        true
                    }
                    event.key == Key.Escape -> {
                        onClearSelection()
                        contextItem = null
                        true
                    }
                    event.key == Key.Delete && state.selectionMode -> {
                        onDeleteSelected()
                        true
                    }
                    event.key == Key.F5 -> {
                        onRefresh()
                        true
                    }
                    event.key == Key.DirectionUp && !state.selectionMode -> {
                        onNavigateUp()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            LargePlacesPane(
                modifier = Modifier.width(224.dp),
                volumes = state.volumes,
                bookmarks = state.bookmarks,
                rootState = state.rootState,
                rootEnabled = state.rootEnabled,
                currentPath = state.currentPath,
                onNavigate = onNavigate,
            )
            VerticalDivider()
            LargeFilePane(
                modifier = Modifier.weight(1f),
                state = state,
                onNavigate = onNavigate,
                onSelectItem = { item ->
                    if (state.selectionMode || item.isDirectory) onOpenItem(item)
                    else previewItem = item
                },
                onOpenItem = onOpenItem,
                onLongClick = onLongClick,
                onNavigateUp = onNavigateUp,
                onRefresh = onRefresh,
                onContextMenu = { contextItem = it },
                previewPath = previewItem?.path,
                onSelectTab = onSelectTab,
                onCloseTab = onCloseTab,
                onAddTab = onAddTab,
                onReorderTabs = onReorderTabs,
            )
            VerticalDivider()
            LargePreviewPane(
                modifier = Modifier.width(320.dp),
                item = previewItem,
            )
        }

        DropdownMenu(
            expanded = contextItem != null,
            onDismissRequest = { contextItem = null },
            offset = DpOffset(224.dp, 72.dp),
            properties = PopupProperties(focusable = true),
        ) {
            val item = contextItem
            if (item != null) {
                DropdownMenuItem(
                    text = { Text("Open") },
                    leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                    onClick = {
                        contextItem = null
                        onOpenItem(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Select") },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                    onClick = {
                        contextItem = null
                        onLongClick(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Properties") },
                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                    onClick = {
                        contextItem = null
                        onShowProperties(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun LargePlacesPane(
    modifier: Modifier,
    volumes: List<StorageVolume>,
    bookmarks: List<BookmarkEntity>,
    rootState: RootState,
    rootEnabled: Boolean,
    currentPath: String,
    onNavigate: (String) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item {
                Text(
                    "PLACES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(volumes, key = { "volume:${it.path}" }) { volume ->
                LargePlaceItem(
                    title = volume.name,
                    subtitle = volume.path,
                    icon = if (volume.isRemovable) Icons.Filled.SdCard else Icons.Filled.Storage,
                    selected = currentPath == volume.path,
                    onClick = { onNavigate(volume.path) },
                )
            }
            item {
                LargePlaceItem(
                    title = "Downloads",
                    subtitle = "/storage/emulated/0/Download",
                    icon = Icons.Filled.Download,
                    selected = currentPath == "/storage/emulated/0/Download",
                    onClick = { onNavigate("/storage/emulated/0/Download") },
                )
            }
            if (bookmarks.isNotEmpty()) {
                item {
                    Text(
                        "BOOKMARKS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                items(bookmarks, key = { "bookmark:${it.path}" }) { bookmark ->
                    LargePlaceItem(
                        title = bookmark.name,
                        subtitle = bookmark.path,
                        icon = Icons.Filled.Bookmark,
                        selected = currentPath == bookmark.path,
                        onClick = { onNavigate(bookmark.path) },
                    )
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                LargePlaceItem(
                    title = if (rootEnabled) "Root enabled" else "Root",
                    subtitle = when (rootState) {
                        RootState.GRANTED -> "Browse /system and /data"
                        RootState.DENIED -> "Not rooted or permission denied"
                        RootState.UNKNOWN -> "Checking root access"
                    },
                    icon = Icons.Filled.Terminal,
                    selected = currentPath == "/",
                    onClick = { onNavigate("/") },
                )
            }
        }
    }
}

@Composable
private fun LargePlaceItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, null) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LargeFilePane(
    modifier: Modifier,
    state: BrowserUiState,
    onNavigate: (String) -> Unit,
    onSelectItem: (FileItem) -> Unit,
    onOpenItem: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onContextMenu: (FileItem) -> Unit,
    previewPath: String?,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddTab: () -> Unit,
    onReorderTabs: (Int, Int) -> Unit,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Files", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Filled.ArrowUpward, "Up one folder")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        }
        BrowserTabsBar(
            tabs = state.tabs,
            selectedIndex = state.selectedTabIndex,
            onSelect = onSelectTab,
            onClose = onCloseTab,
            onAdd = onAddTab,
            onReorder = onReorderTabs,
        )
        BreadcrumbBar(currentPath = state.currentPath, onNavigate = onNavigate)
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (state.files.isEmpty() && !state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        state.error ?: "Empty folder",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (state.viewMode == com.explorer.fileexplorer.core.model.ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(state.files, key = { it.path }) { item ->
                            FileGridItem(
                                item = item,
                                isSelected = item.path == previewPath || item.path in state.selectedItems,
                                selectionMode = state.selectionMode,
                                visibleColumns = state.visibleColumns,
                                onClick = { onSelectItem(item) },
                                onLongClick = { onLongClick(item) },
                                modifier = Modifier
                                    .border(
                                        width = if (item.path == previewPath) 1.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    .pointerInteropFilter { event ->
                                        val secondary = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                                        if (secondary && event.actionMasked == MotionEvent.ACTION_DOWN) {
                                            onContextMenu(item)
                                        }
                                        secondary
                                    },
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.files, key = { it.path }) { item ->
                            FileListItem(
                                item = item,
                                isSelected = item.path == previewPath || item.path in state.selectedItems,
                                selectionMode = state.selectionMode,
                                compact = state.compactDensity,
                                visibleColumns = state.visibleColumns,
                                onClick = { onSelectItem(item) },
                                onLongClick = { onLongClick(item) },
                                modifier = Modifier
                                    .border(
                                        width = if (item.path == previewPath) 1.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    .pointerInteropFilter { event ->
                                        val secondary = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                                        if (secondary && event.actionMasked == MotionEvent.ACTION_DOWN) {
                                            onContextMenu(item)
                                        }
                                        secondary
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LargePreviewPane(
    modifier: Modifier,
    item: FileItem?,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (item == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        Icons.Filled.Preview,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Select a file to preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            FilePreview(item)
        }
    }
}

@Composable
private fun FilePreview(item: FileItem) {
    var textPreview by remember(item.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.path) {
        textPreview = if (item.isText && item.size <= MAX_TEXT_PREVIEW_BYTES && !item.path.contains("://")) {
            withContext(Dispatchers.IO) {
                runCatching {
                    File(item.path).inputStream().bufferedReader().use { it.readText().take(MAX_TEXT_PREVIEW_CHARS) }
                }.getOrNull()
            }
        } else null
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        if (item.isImage && !item.path.contains("://")) {
            AsyncImage(
                model = File(item.path),
                contentDescription = item.name,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        PreviewProperty("Type", if (item.isDirectory) "Folder" else item.mimeType)
        PreviewProperty("Size", item.displaySize)
        PreviewProperty("Path", item.path)
        item.ownerName?.let { PreviewProperty("Owner", it) }
        item.groupName?.let { PreviewProperty("Group", it) }
        if (textPreview != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Text preview", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                textPreview!!,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PreviewProperty(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_TEXT_PREVIEW_BYTES = 512 * 1024L
private const val MAX_TEXT_PREVIEW_CHARS = 12_000
