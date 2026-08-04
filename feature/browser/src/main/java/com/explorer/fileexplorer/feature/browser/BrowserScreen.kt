package com.explorer.fileexplorer.feature.browser

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.database.TagEntity
import com.explorer.fileexplorer.core.designsystem.AccentOrange
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import com.explorer.fileexplorer.core.model.*
import com.explorer.fileexplorer.core.storage.RootState
import com.explorer.fileexplorer.core.ui.BreadcrumbBar
import com.explorer.fileexplorer.core.ui.FileGridItem
import com.explorer.fileexplorer.core.ui.FileListItem
import com.explorer.fileexplorer.feature.security.SecurityEntryPoint
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.mediarouter.app.MediaRouteButton
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
    onOpenCollections: () -> Unit = {},
    onOpenTags: () -> Unit = {},
    onOpenSecurity: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenRootModules: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenAnalyzer: () -> Unit = {},
    onOpenTransfers: () -> Unit = {},
    onOpenEditor: (String) -> Unit = {},
    onOpenHexEditor: (String) -> Unit = {},
    onOpenPreview: (String) -> Unit = {},
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
    val usbTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::addUsbTree)
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                viewModel.refreshUsbStorage()
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(initialPath) {
        initialPath?.takeIf { it.isNotBlank() }?.let(viewModel::navigateTo)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is BrowserEvent.OpenFile -> {
                    val extension = event.item.extension.lowercase()
                    if (event.item.path.startsWith("/") && extension in setOf("pdf", "docx", "xlsx")) {
                        onOpenPreview(event.item.path)
                    } else if (event.item.path.startsWith("/") &&
                        (event.item.isText || extension in setOf("kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "ps1", "md", "txt", "yml", "yaml", "toml", "cfg", "ini", "conf", "log", "csv", "sql", "c", "cpp", "h", "rs", "go", "rb", "php", "swift", "gradle", "properties"))) {
                        onOpenEditor(event.item.path)
                    } else {
                        openFile(context, event.item)
                    }
                }
                is BrowserEvent.ShareFiles -> shareFiles(context, event.items)
                is BrowserEvent.SendNearbyFiles -> sendNearbyFiles(context, event.items)
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
                volumes = state.volumes, usbDevices = state.usbDevices, usbRoots = state.usbRoots,
                bookmarks = state.bookmarks,
                rootState = state.rootState, rootEnabled = state.rootEnabled,
                onNavigate = { path -> viewModel.navigateTo(path); scope.launch { drawerState.close() } },
                onOpenUsbPicker = {
                    scope.launch { drawerState.close() }
                    usbTreePicker.launch(null)
                },
                onOpenCollections = { scope.launch { drawerState.close() }; onOpenCollections() },
                onOpenTags = { scope.launch { drawerState.close() }; onOpenTags() },
                onToggleRoot = { viewModel.toggleRootMode() },
                onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                onOpenNetwork = { scope.launch { drawerState.close() }; onOpenNetwork() },
                onOpenShizuku = { scope.launch { drawerState.close() }; onOpenShizuku() },
                onOpenServer = { scope.launch { drawerState.close() }; onOpenServer() },
                onOpenCloud = { scope.launch { drawerState.close() }; onOpenCloud() },
                onOpenSecurity = { scope.launch { drawerState.close() }; onOpenSecurity() },
                onOpenApps = { scope.launch { drawerState.close() }; onOpenApps() },
                onOpenRootModules = { scope.launch { drawerState.close() }; onOpenRootModules() },
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
                        onSendNearby = viewModel::sendNearbySelected,
                        onCast = viewModel::castSelected,
                        onEncrypt = viewModel::encryptSelected,
                        onDecrypt = viewModel::requestDecryptSelected,
                        onCompress = viewModel::showCompressDialog,
                        onWatchIntegrity = viewModel::watchSelectedIntegrity,
                        onTag = viewModel::showTagDialog,
                        onBatchRename = viewModel::showBatchRenameDialog,
                        onRename = { state.files.firstOrNull { it.path in state.selectedItems }?.let { viewModel.showRename(it) } },
                        onProperties = { state.files.firstOrNull { it.path in state.selectedItems }?.let { viewModel.showProperties(it) } },
                        onHexView = {
                            state.files.firstOrNull { it.path in state.selectedItems }
                                ?.takeIf { !it.isDirectory }
                                ?.let { onOpenHexEditor(it.path) }
                        })
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
                        Icon(Icons.Filled.CreateNewFolder, stringResource(DesignSystemR.string.new_folder))
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
                        onSwipeLeft = { item -> viewModel.applySwipe(item, state.swipeLeftAction) },
                        onSwipeRight = { item -> viewModel.applySwipe(item, state.swipeRightAction) },
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
                            Text(stringResource(DesignSystemR.string.root_mode_active), style = MaterialTheme.typography.labelMedium, color = AccentOrange)
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
                            Text(stringResource(DesignSystemR.string.browsing_archive_read_only), style = MaterialTheme.typography.labelMedium,
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
                        onPrimarySwipeLeft = { item -> viewModel.applySwipe(item, state.swipeLeftAction) },
                        onPrimarySwipeRight = { item -> viewModel.applySwipe(item, state.swipeRightAction) },
                        onSecondaryNavigate = viewModel::navigateSecondaryTo,
                        onSecondaryItemClick = viewModel::onSecondaryItemClick,
                        onSecondaryItemLongClick = viewModel::onSecondaryItemLongClick,
                        onSecondarySwipeLeft = { item -> viewModel.applySwipe(item, state.swipeLeftAction) },
                        onSecondarySwipeRight = { item -> viewModel.applySwipe(item, state.swipeRightAction) },
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
                                            showDirectorySizes = state.showDirectorySizes,
                                            directorySize = state.directorySizes[item.path],
                                            directorySizeUnavailable = item.path in state.directorySizesUnavailable,
                                            onClick = { viewModel.onItemClick(item) },
                                            onLongClick = { viewModel.onItemLongClick(item) },
                                            onSwipeLeft = { viewModel.applySwipe(item, state.swipeLeftAction) },
                                            onSwipeRight = { viewModel.applySwipe(item, state.swipeRightAction) },
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
        InputDialog(title = stringResource(DesignSystemR.string.new_folder), label = stringResource(DesignSystemR.string.folder_name), initial = "",
            confirmText = stringResource(DesignSystemR.string.create),
            onConfirm = viewModel::createFolder, onDismiss = viewModel::dismissNewFolderDialog)
    }
    state.renameItem?.let { item ->
        InputDialog(title = stringResource(DesignSystemR.string.rename), label = stringResource(DesignSystemR.string.new_name), initial = item.name,
            confirmText = stringResource(DesignSystemR.string.rename),
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
    if (state.showTagDialog) {
        TagAssignmentDialog(
            tags = state.tags,
            selectedTags = state.tagDialogSelectedTags,
            onToggle = viewModel::toggleTagInDialog,
            onCreate = viewModel::createTagForDialog,
            onConfirm = viewModel::applyTags,
            onDismiss = viewModel::dismissTagDialog,
        )
    }
    state.deleteConfirmationItem?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(stringResource(if (item.isDirectory) DesignSystemR.string.delete_folder_title else DesignSystemR.string.delete_file_title)) },
            text = { Text(stringResource(DesignSystemR.string.move_to_trash, item.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteGesture) { Text(stringResource(DesignSystemR.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) { Text(stringResource(DesignSystemR.string.cancel)) }
            },
        )
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
        title = { Text(stringResource(DesignSystemR.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Filled.Menu, stringResource(DesignSystemR.string.menu)) } },
        actions = {
            if (insideArchive) {
                IconButton(onClick = onExtractAll) { Icon(Icons.Filled.Unarchive, stringResource(DesignSystemR.string.extract_all)) }
            }
            if (canPaste) { IconButton(onClick = onPaste) { Icon(Icons.Filled.ContentPaste, stringResource(DesignSystemR.string.paste)) } }
            IconButton(onClick = onSearchClick) { Icon(Icons.Filled.Search, stringResource(DesignSystemR.string.search)) }
            CastRouteButton()
            IconButton(onClick = onSortClick) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(DesignSystemR.string.sort)) }
            IconButton(onClick = onViewToggle) {
                Icon(if (viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList, stringResource(DesignSystemR.string.toggle_view))
            }
            IconButton(onClick = onToggleDualPane) {
                Icon(
                    Icons.Filled.ViewColumn,
                    stringResource(if (dualPaneEnabled) DesignSystemR.string.close_dual_pane else DesignSystemR.string.open_dual_pane),
                )
            }
            var moreExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Filled.MoreVert, stringResource(DesignSystemR.string.more)) }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(if (showHidden) DesignSystemR.string.hide_hidden_files else DesignSystemR.string.show_hidden_files_menu)) },
                    onClick = { onToggleHidden(); moreExpanded = false }, leadingIcon = { Icon(Icons.Filled.Visibility, null) })
                HorizontalDivider()
                Text(
                    stringResource(DesignSystemR.string.columns),
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
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.new_folder)) },
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
    onSendNearby: () -> Unit,
    onCast: () -> Unit,
    onEncrypt: () -> Unit, onDecrypt: () -> Unit,
    onCompress: () -> Unit, onWatchIntegrity: () -> Unit, onTag: () -> Unit, onBatchRename: () -> Unit,
    onRename: () -> Unit, onProperties: () -> Unit,
    onHexView: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(DesignSystemR.string.selected_count, selectedCount)) },
        navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Filled.Close, stringResource(DesignSystemR.string.clear)) } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, stringResource(DesignSystemR.string.select_all)) }
            if (!insideArchive) {
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, stringResource(DesignSystemR.string.copy)) }
                IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, stringResource(DesignSystemR.string.cut)) }
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
                    Icon(Icons.Filled.Delete, stringResource(DesignSystemR.string.delete))
                }
            }
            var moreExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Filled.MoreVert, stringResource(DesignSystemR.string.more)) }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                if (!insideArchive) {
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.delete_permanently)) }, onClick = { onPermanentDelete(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.DeleteForever, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.compress)) }, onClick = { onCompress(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.FolderZip, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.watch_for_changes)) }, onClick = { onWatchIntegrity(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.VerifiedUser, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.set_tags)) }, onClick = { onTag(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Label, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.share)) }, onClick = { onShare(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Share, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.send_quick_share)) }, onClick = { onSendNearby(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.NearMe, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.cast_media)) }, onClick = { onCast(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Cast, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.encrypt_files)) }, onClick = { onEncrypt(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Lock, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.decrypt_files)) }, onClick = { onDecrypt(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.LockOpen, null) })
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.batch_rename)) }, onClick = { onBatchRename(); moreExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.EditNote, null) })
                    if (selectedCount == 1) {
                        DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.hex_view)) }, onClick = { onHexView(); moreExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Code, null) })
                        DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.rename)) }, onClick = { onRename(); moreExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    }
                }
                DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.properties)) }, onClick = { onProperties(); moreExpanded = false },
                    leadingIcon = { Icon(Icons.Filled.Info, null) })
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
}

@Composable
private fun CastRouteButton() {
    AndroidView(
        factory = { viewContext ->
            MediaRouteButton(viewContext).also { button ->
                runCatching {
                    CastButtonFactory.setUpMediaRouteButton(viewContext.applicationContext, button)
                }
            }
        },
        modifier = Modifier.size(48.dp),
    )
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignSystemR.string.cancel)) } })
}

@Composable
private fun TagAssignmentDialog(
    tags: List<TagEntity>,
    selectedTags: Set<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignSystemR.string.set_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(DesignSystemR.string.tag_replace_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (tags.isEmpty()) {
                    Text(stringResource(DesignSystemR.string.create_tag_prompt), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(tags, key = { it.name }) { tag ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(tag.name) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = tag.name in selectedTags,
                                    onCheckedChange = { onToggle(tag.name) },
                                )
                                Text("#${tag.name}")
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text(stringResource(DesignSystemR.string.new_tag)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (newTag.isNotBlank()) {
                                onCreate(newTag)
                                newTag = ""
                            }
                        },
                    ) { Text(stringResource(DesignSystemR.string.add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(DesignSystemR.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignSystemR.string.cancel)) } },
    )
}

@Composable
private fun CompressDialog(
    onConfirm: (String, ArchiveFormat, CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("archive") }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(DesignSystemR.string.compress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(DesignSystemR.string.archive_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f },
                            label = { Text(f.extension.uppercase()) })
                    }
                }
                if (format == ArchiveFormat.ZIP) {
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text(stringResource(DesignSystemR.string.password_optional)) }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onConfirm(name.trim(), format, password.ifEmpty { null }?.toCharArray())
            }) { Text(stringResource(DesignSystemR.string.compress)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignSystemR.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertiesSheet(item: FileItem, selinuxContext: String?, onDismiss: () -> Unit, onBookmark: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(item.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            PropRow(stringResource(DesignSystemR.string.file_path), item.path)
            PropRow(stringResource(DesignSystemR.string.file_size), item.displaySize)
            PropRow(stringResource(DesignSystemR.string.file_type), item.mimeType)
            if (item.lastModified > 0) PropRow(stringResource(DesignSystemR.string.modified), java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(item.lastModified)))
            if (item.permissions.isNotEmpty()) PropRow(stringResource(DesignSystemR.string.permissions), item.permissions.joinToString(""))
            item.ownerName?.let { PropRow(stringResource(DesignSystemR.string.owner), it) }
            item.groupName?.let { PropRow(stringResource(DesignSystemR.string.group), it) }
            item.symlinkTarget?.let { PropRow(stringResource(DesignSystemR.string.link_target), it) }
            selinuxContext?.let { PropRow(stringResource(DesignSystemR.string.selinux), it) }
            Spacer(Modifier.height(16.dp))
            if (item.isDirectory) {
                OutlinedButton(onClick = onBookmark, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(DesignSystemR.string.toggle_bookmark))
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
        val uri = item.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.provider", File(item.path))
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, context.getString(DesignSystemR.string.open_with)))
    } catch (_: Exception) { Toast.makeText(context, context.getString(DesignSystemR.string.no_app_found), Toast.LENGTH_SHORT).show() }
}

private fun shareFiles(context: android.content.Context, items: List<FileItem>) {
    launchShare(context, items, "Share")
}

private fun sendNearbyFiles(context: android.content.Context, items: List<FileItem>) {
    launchShare(context, items, "Send with Quick Share")
}

private fun launchShare(context: android.content.Context, items: List<FileItem>, chooserTitle: String) {
    try {
        val shareItems = items.mapNotNull { item ->
            try {
                (item.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.provider", File(item.path)))
                    .let { uri -> item to uri }
            }
            catch (_: Exception) { null }
        }
        if (shareItems.isEmpty()) return
        val uris = shareItems.map { it.second }
        val intent = if (shareItems.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = shareItems.first().first.mimeType
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = "*/*"
            }
        }
        intent.clipData = ClipData.newUri(context.contentResolver, shareItems.first().first.name, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra(Intent.EXTRA_TITLE, chooserTitle)
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    } catch (_: Exception) { Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show() }
}
