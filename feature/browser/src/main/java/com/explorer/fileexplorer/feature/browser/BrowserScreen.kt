package com.explorer.fileexplorer.feature.browser

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.designsystem.AccentOrange
import com.explorer.fileexplorer.core.model.*
import com.explorer.fileexplorer.core.storage.RootState
import com.explorer.fileexplorer.core.ui.BreadcrumbBar
import com.explorer.fileexplorer.core.ui.FileGridItem
import com.explorer.fileexplorer.core.ui.FileListItem
import com.explorer.fileexplorer.feature.security.SecurityEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    initialPath: String? = null,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenShizuku: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenCloud: () -> Unit = {},
    onOpenSecurity: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenAnalyzer: () -> Unit = {},
    onOpenTransfers: () -> Unit = {},
    onOpenEditor: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val securityEntryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SecurityEntryPoint::class.java,
        )
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(initialPath) {
        initialPath?.takeIf { it.isNotBlank() }?.let(viewModel::navigateTo)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is BrowserEvent.OpenFile -> {
                    // Open text files in built-in editor
                    if (event.item.isText || event.item.extension in setOf("kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "ps1", "md", "txt", "yml", "yaml", "toml", "cfg", "ini", "conf", "log", "csv", "sql", "c", "cpp", "h", "rs", "go", "rb", "php", "swift", "gradle", "properties")) {
                        onOpenEditor(event.item.path)
                    } else {
                        openFile(context, event.item)
                    }
                }
                is BrowserEvent.ShareFiles -> shareFiles(context, event.items)
                is BrowserEvent.RequestDecrypt -> {
                    val activity = context as? FragmentActivity
                    if (activity == null) {
                        Toast.makeText(context, "Biometric authentication unavailable", Toast.LENGTH_SHORT).show()
                    } else {
                        securityEntryPoint.biometricHelper().showBiometricPrompt(
                            activity = activity,
                            title = "Decrypt files",
                            subtitle = "Authenticate to decrypt selected files",
                            onSuccess = { viewModel.decryptFiles(event.paths) },
                            onFailure = { reason ->
                                Toast.makeText(context, "Decryption cancelled: $reason", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = state.selectionMode || state.insideArchive || state.currentPath != "/") {
        when {
            state.selectionMode -> viewModel.clearSelection()
            else -> viewModel.navigateUp()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NavigationDrawerContent(
                volumes = state.volumes, bookmarks = state.bookmarks,
                rootState = state.rootState, rootEnabled = state.rootEnabled,
                onNavigate = { path -> viewModel.navigateTo(path); scope.launch { drawerState.close() } },
                onToggleRoot = { viewModel.toggleRootMode() },
                onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                onOpenNetwork = { scope.launch { drawerState.close() }; onOpenNetwork() },
                onOpenShizuku = { scope.launch { drawerState.close() }; onOpenShizuku() },
                onOpenServer = { scope.launch { drawerState.close() }; onOpenServer() },
                onOpenCloud = { scope.launch { drawerState.close() }; onOpenCloud() },
                onOpenSecurity = { scope.launch { drawerState.close() }; onOpenSecurity() },
                onOpenApps = { scope.launch { drawerState.close() }; onOpenApps() },
                onOpenTrash = { scope.launch { drawerState.close() }; onOpenTrash() },
                onOpenAnalyzer = { scope.launch { drawerState.close() }; onOpenAnalyzer() },
                onOpenTransfers = { scope.launch { drawerState.close() }; onOpenTransfers() })
        },
    ) {
        Scaffold(
            topBar = {
                if (state.selectionMode) {
                    SelectionTopBar(
                        selectedCount = state.selectedCount,
                        insideArchive = state.insideArchive,
                        onClear = viewModel::clearSelection, onSelectAll = viewModel::selectAll,
                        onCopy = viewModel::copySelected, onCut = viewModel::cutSelected,
                        onDelete = viewModel::deleteSelected,
                        onPermanentDelete = viewModel::permanentlyDeleteSelected,
                        onShare = viewModel::shareSelected,
                        onEncrypt = viewModel::encryptSelected,
                        onDecrypt = viewModel::requestDecryptSelected,
                        onCompress = viewModel::showCompressDialog,
                        onBatchRename = viewModel::showBatchRenameDialog,
                        onRename = { state.files.firstOrNull { it.path in state.selectedItems }?.let { viewModel.showRename(it) } },
                        onProperties = { state.files.firstOrNull { it.path in state.selectedItems }?.let { viewModel.showProperties(it) } })
                } else {
                    BrowserTopBar(
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSearchClick = onOpenSearch, onSortClick = { showSortMenu = true },
                        onViewToggle = viewModel::toggleViewMode, onToggleHidden = viewModel::toggleHidden,
                        onToggleColumn = viewModel::toggleColumn,
                        onNewFolder = viewModel::showNewFolderDialog,
                        viewMode = state.viewMode, visibleColumns = state.visibleColumns, showHidden = state.showHidden,
                        canPaste = state.canPaste, onPaste = viewModel::paste,
                        rootEnabled = state.rootEnabled, isRootPath = state.isRootPath,
                        insideArchive = state.insideArchive,
                        dualPaneEnabled = state.dualPaneEnabled,
                        onToggleDualPane = viewModel::toggleDualPane,
                        onExtractAll = { viewModel.extractArchive() })
                }
            },
            floatingActionButton = {
                if (!state.selectionMode && !state.insideArchive) {
                    FloatingActionButton(onClick = viewModel::showNewFolderDialog,
                        containerColor = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Filled.CreateNewFolder, "New Folder")
                    }
                }
            },
        ) { padding ->
            BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (!state.dualPaneEnabled && LargeScreenLayoutPolicy.useThreePane(maxWidth.value.toInt())) {
                    LargeScreenBrowserContent(
                        state = state,
                        onNavigate = viewModel::navigateTo,
                        onOpenItem = viewModel::onItemClick,
                        onLongClick = viewModel::onItemLongClick,
                        onNavigateUp = viewModel::navigateUp,
                        onRefresh = viewModel::refresh,
                        onSelectAll = viewModel::selectAll,
                        onClearSelection = viewModel::clearSelection,
                        onDeleteSelected = viewModel::deleteSelected,
                        onShowProperties = viewModel::showProperties,
                        onSelectTab = { viewModel.selectTab(BrowserPane.PRIMARY, it) },
                        onCloseTab = { viewModel.closeTab(BrowserPane.PRIMARY, it) },
                        onAddTab = { viewModel.openTab(BrowserPane.PRIMARY) },
                        onReorderTabs = { from, to -> viewModel.reorderTabs(BrowserPane.PRIMARY, from, to) },
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                // Root mode banner
                if (state.rootEnabled && state.isRootPath) {
                    Surface(color = AccentOrange.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Root mode active", style = MaterialTheme.typography.labelMedium, color = AccentOrange)
                            state.selinuxContext?.let { ctx ->
                                Spacer(Modifier.weight(1f))
                                Text(ctx, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }

                // Archive banner
                if (state.insideArchive) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FolderZip, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Browsing archive (read-only)", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (!state.dualPaneEnabled) {
                    BrowserTabsBar(
                        tabs = state.tabs,
                        selectedIndex = state.selectedTabIndex,
                        onSelect = { viewModel.selectTab(BrowserPane.PRIMARY, it) },
                        onClose = { viewModel.closeTab(BrowserPane.PRIMARY, it) },
                        onAdd = { viewModel.openTab(BrowserPane.PRIMARY) },
                        onReorder = { from, to -> viewModel.reorderTabs(BrowserPane.PRIMARY, from, to) },
                    )
                    // Breadcrumb
                    BreadcrumbBar(currentPath = state.currentPath, onNavigate = viewModel::navigateTo)
                }

                // Sort menu
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                val newDir = if (state.sortOrder.field == field)
                                    if (state.sortOrder.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
                                else SortDirection.ASCENDING
                                viewModel.setSortOrder(state.sortOrder.copy(field = field, direction = newDir))
                                showSortMenu = false
                            },
                            trailingIcon = {
                                if (state.sortOrder.field == field) Icon(
                                    if (state.sortOrder.direction == SortDirection.ASCENDING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                    null, modifier = Modifier.size(16.dp))
                            })
                    }
                }

                if (state.dualPaneEnabled) {
                    DualPaneContent(
                        state = state,
                        onPrimaryNavigate = viewModel::navigateTo,
                        onPrimaryItemClick = viewModel::onItemClick,
                        onPrimaryItemLongClick = viewModel::onItemLongClick,
                        onSecondaryNavigate = viewModel::navigateSecondaryTo,
                        onSecondaryItemClick = viewModel::onSecondaryItemClick,
                        onSecondaryItemLongClick = viewModel::onSecondaryItemLongClick,
                        onPrimaryNavigateUp = viewModel::navigateUp,
                        onSecondaryNavigateUp = viewModel::navigateSecondaryUp,
                        onPrimaryRefresh = viewModel::refresh,
                        onSecondaryRefresh = viewModel::refreshSecondary,
                        onSelectTab = viewModel::selectTab,
                        onCloseTab = viewModel::closeTab,
                        onAddTab = viewModel::openTab,
                        onReorderTabs = viewModel::reorderTabs,
                        onRequestDrop = viewModel::requestDrop,
                    )
                } else {
                    // File list
                    PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()) {
                        if (state.files.isEmpty() && !state.isLoading) {
                            EmptyState(state.error)
                        } else {
                            if (state.viewMode == ViewMode.GRID) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 140.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    gridItems(items = state.files, key = { it.path }) { item ->
                                        FileGridItem(
                                            item = item,
                                            isSelected = item.path in state.selectedItems,
                                            selectionMode = state.selectionMode,
                                            visibleColumns = state.visibleColumns,
                                            onClick = { viewModel.onItemClick(item) },
                                            onLongClick = { viewModel.onItemLongClick(item) },
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(items = state.files, key = { it.path }) { item ->
                                        FileListItem(
                                            item = item,
                                            isSelected = item.path in state.selectedItems,
                                            selectionMode = state.selectionMode,
                                            compact = state.compactDensity,
                                            visibleColumns = state.visibleColumns,
                                            onClick = { viewModel.onItemClick(item) },
                                            onLongClick = { viewModel.onItemLongClick(item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                    }
                }
            }
        }
    }

    // Dialogs
    if (state.showNewFolderDialog) {
        InputDialog(title = "New Folder", label = "Folder name", initial = "",
            confirmText = "Create",
            onConfirm = viewModel::createFolder, onDismiss = viewModel::dismissNewFolderDialog)
    }
    state.renameItem?.let { item ->
        InputDialog(title = "Rename", label = "New name", initial = item.name,
            confirmText = "Rename",
            onConfirm = { newName -> viewModel.rename(item.path, newName) }, onDismiss = viewModel::dismissRename)
    }
    if (state.showBatchRenameDialog) {
        BatchRenameDialog(
            items = state.files.filter { it.path in state.selectedItems },
            onConfirm = viewModel::batchRename,
            onDismiss = viewModel::dismissBatchRenameDialog,
        )
    }
    state.propertiesItem?.let { item ->
        PropertiesSheet(item = item, selinuxContext = state.selinuxContext,
            onDismiss = viewModel::dismissProperties,
            onBookmark = { viewModel.toggleBookmark(item.path, item.name) })
    }
    if (state.showCompressDialog) {
        CompressDialog(
            onConfirm = { name, format, password -> viewModel.compressSelected(name, format, password) },
            onDismiss = viewModel::dismissCompressDialog)
    }
    state.pendingDrop?.let { request ->
        DropConfirmationDialog(
            request = request,
            onCopy = { viewModel.confirmDrop(move = false) },
            onMove = { viewModel.confirmDrop(move = true) },
            onDismiss = viewModel::dismissDrop,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    onMenuClick: () -> Unit, onSearchClick: () -> Unit, onSortClick: () -> Unit,
    onViewToggle: () -> Unit, onToggleHidden: () -> Unit, onToggleColumn: (FileColumn) -> Unit,
    onNewFolder: () -> Unit, viewMode: ViewMode, visibleColumns: Set<FileColumn>, showHidden: Boolean,
    canPaste: Boolean, onPaste: () -> Unit,
    rootEnabled: Boolean, isRootPath: Boolean, insideArchive: Boolean,
    dualPaneEnabled: Boolean, onToggleDualPane: () -> Unit, onExtractAll: () -> Unit,
) {
    TopAppBar(
        title = { Text("File Explorer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Filled.Menu, "Menu") } },
        actions = {
            if (insideArchive) {
                IconButton(onClick = onExtractAll) { Icon(Icons.Filled.Unarchive, "Extract All") }
            }
            if (canPaste) { IconButton(onClick = onPaste) { Icon(Icons.Filled.ContentPaste, "Paste") } }
            IconButton(onClick = onSearchClick) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = onSortClick) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
            IconButton(onClick = onViewToggle) {
                Icon(if (viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList, "Toggle View")
            }
            IconButton(onClick = onToggleDualPane) {
                Icon(
                    Icons.Filled.ViewColumn,
                    if (dualPaneEnabled) "Close dual-pane view" else "Open dual-pane view",
                )
            }
            var moreExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
                    onClick = { onToggleHidden(); moreExpanded = false }, leadingIcon = { Icon(Icons.Filled.Visibility, null) })
                HorizontalDivider()
                Text(
                    "Columns",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                FileColumn.entries.forEach { column ->
                    DropdownMenuItem(
                        text = { Text(column.label) },
                        onClick = { onToggleColumn(column) },
                        leadingIcon = { Icon(Icons.Filled.ViewColumn, null) },
                        trailingIcon = {
                            Checkbox(
                                checked = column in visibleColumns,
                                onCheckedChange = null,
                            )
                        },
                    )
                }
                if (!insideArchive) {
                    DropdownMenuItem(text = { Text("New folder") },
                        onClick = { onNewFolder(); moreExpanded = false }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (rootEnabled && isRootPath) AccentOrange.copy(alpha = 0.05f)
            else MaterialTheme.colorScheme.surface))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int, insideArchive: Boolean,
    onClear: () -> Unit, onSelectAll: () -> Unit,
    onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit, onPermanentDelete: () -> Unit, onShare: () -> Unit,
    onEncrypt: () -> Unit, onDecrypt: () -> Unit,
    onCompress: () -> Unit, onBatchRename: () -> Unit, onRename: () -> Unit, onProperties: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear") } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select All") }
            if (!insideArchive) {
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
                IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, "Cut") }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            onClick = onDelete,
                            onLongClick = onPermanentDelete,
                            role = Role.Button,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Delete, "Delete")
                }
            }
            var moreExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                if (!insideArchive) {
                    DropdownMenuItem(text = { Text("Delete permanently") }, onClick = { onPermanentDelete(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.DeleteForever, null) })
                    DropdownMenuItem(text = { Text("Compress") }, onClick = { onCompress(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.FolderZip, null) })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { onShare(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Share, null) })
                    DropdownMenuItem(text = { Text("Encrypt files") }, onClick = { onEncrypt(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Lock, null) })
                    DropdownMenuItem(text = { Text("Decrypt files") }, onClick = { onDecrypt(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.LockOpen, null) })
                    DropdownMenuItem(text = { Text("Batch rename") }, onClick = { onBatchRename(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.EditNote, null) })
                    if (selectedCount == 1) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { onRename(); moreExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    }
                }
                DropdownMenuItem(text = { Text("Properties") }, onClick = { onProperties(); moreExpanded = false },
                    leadingIcon = { Icon(Icons.Filled.Info, null) })
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
}

@Composable
private fun EmptyState(error: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text(error ?: "Empty folder", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InputDialog(title: String, label: String, initial: String, confirmText: String,
                        onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CompressDialog(
    onConfirm: (String, ArchiveFormat, CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("archive") }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Compress") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Archive name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f },
                            label = { Text(f.extension.uppercase()) })
                    }
                }
                if (format == ArchiveFormat.ZIP) {
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Password (optional)") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onConfirm(name.trim(), format, password.ifEmpty { null }?.toCharArray())
            }) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertiesSheet(item: FileItem, selinuxContext: String?, onDismiss: () -> Unit, onBookmark: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(item.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            PropRow("Path", item.path)
            PropRow("Size", item.displaySize)
            PropRow("Type", item.mimeType)
            if (item.lastModified > 0) PropRow("Modified", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(item.lastModified)))
            if (item.permissions.isNotEmpty()) PropRow("Permissions", item.permissions.joinToString(""))
            item.ownerName?.let { PropRow("Owner", it) }
            item.groupName?.let { PropRow("Group", it) }
            item.symlinkTarget?.let { PropRow("Link target", it) }
            selinuxContext?.let { PropRow("SELinux", it) }
            Spacer(Modifier.height(16.dp))
            if (item.isDirectory) {
                OutlinedButton(onClick = onBookmark, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text("Toggle Bookmark")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PropRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun openFile(context: android.content.Context, item: FileItem) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", File(item.path))
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Open with"))
    } catch (_: Exception) { Toast.makeText(context, "No app found", Toast.LENGTH_SHORT).show() }
}

private fun shareFiles(context: android.content.Context, items: List<FileItem>) {
    try {
        val uris = items.mapNotNull { try { FileProvider.getUriForFile(context, "${context.packageName}.provider", File(it.path)) } catch (_: Exception) { null } }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()); type = items.first().mimeType }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)); type = "*/*" }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share"))
    } catch (e: Exception) { Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show() }
}
