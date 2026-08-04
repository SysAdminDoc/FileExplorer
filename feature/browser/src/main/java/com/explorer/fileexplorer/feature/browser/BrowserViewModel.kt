package com.explorer.fileexplorer.feature.browser

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.data.ArchiveHelper
import com.explorer.fileexplorer.core.data.BatchRenameEngine
import com.explorer.fileexplorer.core.data.BatchRenameOptions
import com.explorer.fileexplorer.core.data.FileRepository
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.data.LocalFileRepository
import com.explorer.fileexplorer.core.data.LocalTrashManager
import com.explorer.fileexplorer.core.data.RootFileRepository
import com.explorer.fileexplorer.core.data.SecureDelete
import com.explorer.fileexplorer.core.data.TagRepository
import com.explorer.fileexplorer.feature.security.FileEncryptionManager
import com.explorer.fileexplorer.feature.security.FileEncryptionBatchResult
import com.explorer.fileexplorer.feature.security.SecurityRepository
import com.explorer.fileexplorer.feature.security.IntegrityRepository
import com.explorer.fileexplorer.feature.transfer.TransferQueueManager
import com.explorer.fileexplorer.core.database.BookmarkDao
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.DirectoryViewPreferenceCodec
import com.explorer.fileexplorer.core.database.DirectoryViewPreferenceDao
import com.explorer.fileexplorer.core.database.RecentFileDao
import com.explorer.fileexplorer.core.database.RecentFileEntity
import com.explorer.fileexplorer.core.database.TagEntity
import com.explorer.fileexplorer.core.model.*
import com.explorer.fileexplorer.core.storage.RootHelper
import com.explorer.fileexplorer.core.storage.RootState
import com.explorer.fileexplorer.core.storage.StorageVolumeHelper
import com.explorer.fileexplorer.feature.settings.SettingsRepository
import com.explorer.fileexplorer.feature.settings.SwipeAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class BrowserTab(
    val id: Long,
    val path: String,
)

data class BrowserUiState(
    val currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortOrder: SortOrder = SortOrder(),
    val viewMode: ViewMode = ViewMode.LIST,
    val visibleColumns: Set<FileColumn> = FileColumn.DEFAULT_VISIBLE_COLUMNS,
    val showHidden: Boolean = false,
    val selectedItems: Set<String> = emptySet(),
    val clipboard: ClipboardContent = ClipboardContent(),
    val volumes: List<StorageVolume> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val showTagDialog: Boolean = false,
    val tagDialogSelectedTags: Set<String> = emptySet(),
    val pathHistory: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val propertiesItem: FileItem? = null,
    val renameItem: FileItem? = null,
    val showBatchRenameDialog: Boolean = false,
    val showNewFolderDialog: Boolean = false,
    val rootState: RootState = RootState.UNKNOWN,
    val rootEnabled: Boolean = false,
    val selinuxContext: String? = null,
    val insideArchive: Boolean = false,
    val archivePath: String? = null,
    val archiveInternalPath: String = "",
    val showCompressDialog: Boolean = false,
    val deleteConfirmationItem: FileItem? = null,
    val showExtractDialog: Boolean = false,
    val compactDensity: Boolean = false,
    val confirmDelete: Boolean = false,
    val swipeLeftAction: SwipeAction = SwipeAction.NONE,
    val swipeRightAction: SwipeAction = SwipeAction.NONE,
    val dualPaneEnabled: Boolean = false,
    val secondaryPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val secondaryFiles: List<FileItem> = emptyList(),
    val secondaryIsLoading: Boolean = false,
    val secondaryError: String? = null,
    val secondarySelectedItems: Set<String> = emptySet(),
    val pendingDrop: PendingDrop? = null,
    val tabs: List<BrowserTab> = listOf(
        BrowserTab(1L, Environment.getExternalStorageDirectory().absolutePath),
    ),
    val selectedTabIndex: Int = 0,
    val secondaryTabs: List<BrowserTab> = listOf(
        BrowserTab(2L, Environment.getExternalStorageDirectory().absolutePath),
    ),
    val secondarySelectedTabIndex: Int = 0,
) {
    val selectionMode: Boolean get() = selectedItems.isNotEmpty()
    val selectedCount: Int get() = selectedItems.size
    val canGoBack: Boolean get() = historyIndex > 0 || insideArchive
    val canGoForward: Boolean get() = historyIndex < pathHistory.lastIndex
    val canPaste: Boolean get() = clipboard.items.isNotEmpty() && !insideArchive
    val isRootPath: Boolean get() = currentPath.let { p ->
        listOf("/data", "/system", "/vendor", "/product", "/efs").any { p.startsWith(it) }
    }
}

enum class BrowserPane {
    PRIMARY, SECONDARY,
}

data class PendingDrop(
    val items: List<FileItem>,
    val destinationPath: String,
    val sourcePane: BrowserPane,
    val destinationPane: BrowserPane,
)

sealed interface BrowserEvent {
    data class Toast(val message: String) : BrowserEvent
    data class OpenFile(val item: FileItem) : BrowserEvent
    data class ShareFiles(val items: List<FileItem>) : BrowserEvent
    data class RequestDecrypt(val paths: List<String>) : BrowserEvent
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val repoFactory: FileRepositoryFactory,
    private val rootHelper: RootHelper,
    private val rootRepo: RootFileRepository,
    private val archiveHelper: ArchiveHelper,
    private val storageVolumeHelper: StorageVolumeHelper,
    private val trashManager: LocalTrashManager,
    private val settingsRepository: SettingsRepository,
    private val securityRepository: SecurityRepository,
    private val fileEncryptionManager: FileEncryptionManager,
    private val integrityRepository: IntegrityRepository,
    private val tagRepository: TagRepository,
    private val transferQueueManager: TransferQueueManager,
    private val bookmarkDao: BookmarkDao,
    private val recentFileDao: RecentFileDao,
    private val directoryViewPreferenceDao: DirectoryViewPreferenceDao,
) : ViewModel() {

    private var nextTabId = 3L

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BrowserEvent>()
    val events: SharedFlow<BrowserEvent> = _events.asSharedFlow()

    init {
        loadVolumes()
        observeBookmarks()
        observeTags()
        observeRootState()
        observeSettings()
        initializeRoot()
        navigateTo(_state.value.currentPath)
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update {
                    it.copy(
                        compactDensity = s.compactDensity,
                        confirmDelete = s.confirmDelete,
                        swipeLeftAction = s.swipeLeftAction,
                        swipeRightAction = s.swipeRightAction,
                    )
                }
            }
        }
    }

    private fun initializeRoot() {
        viewModelScope.launch { rootHelper.initialize() }
    }

    private fun observeRootState() {
        viewModelScope.launch {
            rootHelper.rootState.collect { state -> _state.update { it.copy(rootState = state) } }
        }
        viewModelScope.launch {
            rootHelper.rootEnabled.collect { enabled -> _state.update { it.copy(rootEnabled = enabled) } }
        }
    }

    fun toggleRootMode() {
        if (rootHelper.isRooted) {
            rootHelper.setRootEnabled(!rootHelper.rootEnabled.value)
            refresh()
        } else {
            viewModelScope.launch { _events.emit(BrowserEvent.Toast("Root not available")) }
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            val preferences = DirectoryViewPreferenceCodec.decode(directoryViewPreferenceDao.getByPath(path))
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    selectedItems = emptySet(),
                    sortOrder = preferences.sortOrder,
                    viewMode = preferences.viewMode,
                    visibleColumns = preferences.visibleColumns,
                    tabs = updateTabPath(it.tabs, it.selectedTabIndex, path),
                )
            }
            val repo = repoFactory.getRepository(path)
            repo.listFiles(path).collect { files ->
                val sorted = sortFiles(files, preferences.sortOrder, _state.value.showHidden)
                val history = _state.value.pathHistory.toMutableList()
                val idx = _state.value.historyIndex
                if (idx < history.lastIndex) { while (history.size > idx + 1) history.removeAt(history.lastIndex) }
                history.add(path)
                val selinux = if (_state.value.rootEnabled && rootHelper.requiresRoot(path)) {
                    rootRepo.getSelinuxContext(path)
                } else null
                _state.update {
                    it.copy(currentPath = path, files = sorted, isLoading = false,
                        pathHistory = history, historyIndex = history.lastIndex,
                        insideArchive = false, archivePath = null, archiveInternalPath = "",
                        selinuxContext = selinux)
                }
            }
        }
    }

    fun toggleDualPane() {
        val current = _state.value
        if (current.insideArchive) {
            viewModelScope.launch {
                _events.emit(BrowserEvent.Toast("Close the archive before opening dual-pane view"))
            }
            return
        }

        val enabled = !current.dualPaneEnabled
        _state.update {
            it.copy(
                dualPaneEnabled = enabled,
                secondarySelectedItems = emptySet(),
                pendingDrop = null,
            )
        }
        if (enabled) {
            navigateSecondaryTo(current.secondaryPath.ifBlank { current.currentPath })
        }
    }

    fun navigateSecondaryTo(path: String) {
        if (!_state.value.dualPaneEnabled) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    secondaryIsLoading = true,
                    secondaryError = null,
                    secondarySelectedItems = emptySet(),
                    secondaryTabs = updateTabPath(
                        it.secondaryTabs,
                        it.secondarySelectedTabIndex,
                        path,
                    ),
                )
            }
            try {
                repoFactory.getRepository(path).listFiles(path).collect { files ->
                    _state.update {
                        it.copy(
                            secondaryPath = path,
                            secondaryFiles = sortFiles(files, it.sortOrder, it.showHidden),
                            secondaryIsLoading = false,
                            secondaryError = null,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(secondaryIsLoading = false, secondaryError = "Unable to open folder: ${e.message}")
                }
            }
        }
    }

    fun navigateSecondaryUp() {
        val path = _state.value.secondaryPath
        val parent = path.substringBeforeLast('/', "/")
        if (parent.isNotEmpty() && parent != path) navigateSecondaryTo(parent)
    }

    fun refreshSecondary() {
        navigateSecondaryTo(_state.value.secondaryPath)
    }

    fun openTab(pane: BrowserPane) {
        val state = _state.value
        val path = if (pane == BrowserPane.PRIMARY) state.currentPath else state.secondaryPath
        val tab = BrowserTab(nextTabId++, path)
        if (pane == BrowserPane.PRIMARY) {
            _state.update { it.copy(tabs = it.tabs + tab, selectedTabIndex = it.tabs.size) }
            navigateTo(path)
        } else {
            _state.update {
                it.copy(
                    secondaryTabs = it.secondaryTabs + tab,
                    secondarySelectedTabIndex = it.secondaryTabs.size,
                )
            }
            navigateSecondaryTo(path)
        }
    }

    fun selectTab(pane: BrowserPane, index: Int) {
        val state = _state.value
        if (pane == BrowserPane.PRIMARY) {
            val tab = state.tabs.getOrNull(index) ?: return
            if (index == state.selectedTabIndex) return
            _state.update { it.copy(selectedTabIndex = index) }
            navigateTo(tab.path)
        } else {
            val tab = state.secondaryTabs.getOrNull(index) ?: return
            if (index == state.secondarySelectedTabIndex) return
            _state.update { it.copy(secondarySelectedTabIndex = index) }
            navigateSecondaryTo(tab.path)
        }
    }

    fun closeTab(pane: BrowserPane, index: Int) {
        val state = _state.value
        if (pane == BrowserPane.PRIMARY) {
            if (state.tabs.size <= 1 || state.tabs.getOrNull(index) == null) return
            val tabs = state.tabs.toMutableList().apply { removeAt(index) }
            val selected = TabIndexPolicy.selectedIndexAfterClose(state.selectedTabIndex, index, tabs.lastIndex)
            _state.update { it.copy(tabs = tabs, selectedTabIndex = selected) }
            if (index == state.selectedTabIndex) navigateTo(tabs[selected].path)
        } else {
            if (state.secondaryTabs.size <= 1 || state.secondaryTabs.getOrNull(index) == null) return
            val tabs = state.secondaryTabs.toMutableList().apply { removeAt(index) }
            val selected = TabIndexPolicy.selectedIndexAfterClose(
                state.secondarySelectedTabIndex,
                index,
                tabs.lastIndex,
            )
            _state.update { it.copy(secondaryTabs = tabs, secondarySelectedTabIndex = selected) }
            if (index == state.secondarySelectedTabIndex) navigateSecondaryTo(tabs[selected].path)
        }
    }

    fun reorderTabs(pane: BrowserPane, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        _state.update { state ->
            if (pane == BrowserPane.PRIMARY) {
                val tabs = state.tabs.toMutableList()
                if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return@update state
                val tab = tabs.removeAt(fromIndex)
                tabs.add(toIndex, tab)
                state.copy(
                    tabs = tabs,
                    selectedTabIndex = TabIndexPolicy.moveSelectedIndex(
                        state.selectedTabIndex,
                        fromIndex,
                        toIndex,
                    ),
                )
            } else {
                val tabs = state.secondaryTabs.toMutableList()
                if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return@update state
                val tab = tabs.removeAt(fromIndex)
                tabs.add(toIndex, tab)
                state.copy(
                    secondaryTabs = tabs,
                    secondarySelectedTabIndex = TabIndexPolicy.moveSelectedIndex(
                        state.secondarySelectedTabIndex,
                        fromIndex,
                        toIndex,
                    ),
                )
            }
        }
    }

    fun onSecondaryItemClick(item: FileItem) {
        if (_state.value.secondarySelectedItems.isNotEmpty()) {
            toggleSecondarySelection(item.path)
            return
        }
        if (item.isArchive && archiveHelper.isArchive(item.path)) {
            viewModelScope.launch {
                _events.emit(BrowserEvent.Toast("Close dual-pane view to browse an archive"))
            }
        } else if (item.isDirectory) {
            navigateSecondaryTo(item.path)
        } else {
            viewModelScope.launch {
                recentFileDao.upsert(
                    RecentFileEntity(name = item.name, path = item.path, mimeType = item.mimeType, size = item.size),
                )
                _events.emit(BrowserEvent.OpenFile(item))
            }
        }
    }

    fun onSecondaryItemLongClick(item: FileItem) {
        toggleSecondarySelection(item.path)
    }

    fun toggleSecondarySelection(path: String) {
        _state.update { state ->
            val selected = state.secondarySelectedItems.toMutableSet()
            if (!selected.add(path)) selected.remove(path)
            state.copy(secondarySelectedItems = selected)
        }
    }

    fun requestDrop(
        items: List<FileItem>,
        destinationPath: String,
        sourcePane: BrowserPane,
        destinationPane: BrowserPane,
    ) {
        val validItems = items.filter { DropPathPolicy.canDrop(it.path, destinationPath) }
        if (validItems.isEmpty()) {
            viewModelScope.launch {
                _events.emit(BrowserEvent.Toast("Nothing can be dropped into this folder"))
            }
            return
        }
        _state.update {
            it.copy(
                pendingDrop = PendingDrop(
                    items = validItems,
                    destinationPath = destinationPath,
                    sourcePane = sourcePane,
                    destinationPane = destinationPane,
                ),
            )
        }
    }

    fun dismissDrop() {
        _state.update { it.copy(pendingDrop = null) }
    }

    fun confirmDrop(move: Boolean) {
        val request = _state.value.pendingDrop ?: return
        _state.update { it.copy(pendingDrop = null) }
        transferQueueManager.enqueue(
            operation = if (move) FileOperation.MOVE else FileOperation.COPY,
            sourcePaths = request.items.map { it.path },
            destination = request.destinationPath,
        )
        viewModelScope.launch {
            _events.emit(BrowserEvent.Toast("${request.items.size} item(s) queued for ${if (move) "move" else "copy"}"))
            clearPaneSelection(request.sourcePane)
        }
    }

    fun navigateUp() {
        if (_state.value.insideArchive) {
            val ip = _state.value.archiveInternalPath
            if (ip.contains('/')) {
                navigateInsideArchive(_state.value.archivePath!!, ip.substringBeforeLast('/'))
            } else { navigateTo(_state.value.archivePath!!.substringBeforeLast('/')) }
            return
        }
        val parent = _state.value.currentPath.substringBeforeLast('/', "/")
        if (parent.isNotEmpty() && parent != _state.value.currentPath) navigateTo(parent)
    }

    fun goBack() {
        if (_state.value.insideArchive) { navigateUp(); return }
        val s = _state.value
        if (s.canGoBack) loadPath(s.pathHistory[s.historyIndex - 1], s.historyIndex - 1)
    }

    fun goForward() {
        val s = _state.value
        if (s.canGoForward) loadPath(s.pathHistory[s.historyIndex + 1], s.historyIndex + 1)
    }

    private fun loadPath(path: String, historyIndex: Int) {
        viewModelScope.launch {
            val preferences = DirectoryViewPreferenceCodec.decode(directoryViewPreferenceDao.getByPath(path))
            _state.update { it.copy(isLoading = true, selectedItems = emptySet()) }
            repoFactory.getRepository(path).listFiles(path).collect { files ->
                _state.update {
                    it.copy(
                        currentPath = path,
                        files = sortFiles(files, preferences.sortOrder, it.showHidden),
                        sortOrder = preferences.sortOrder,
                        viewMode = preferences.viewMode,
                        visibleColumns = preferences.visibleColumns,
                        isLoading = false,
                        historyIndex = historyIndex,
                    )
                }
            }
        }
    }

    private fun navigateInsideArchive(archivePath: String, internalPath: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val entries = archiveHelper.listArchive(archivePath, internalPath)
                _state.update { it.copy(files = entries, isLoading = false, insideArchive = true,
                    archivePath = archivePath, archiveInternalPath = internalPath,
                    currentPath = "$archivePath/$internalPath".trimEnd('/')) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Archive error: ${e.message}") }
            }
        }
    }

    fun onItemClick(item: FileItem) {
        if (_state.value.selectionMode) { toggleSelection(item.path); return }
        if (_state.value.insideArchive) {
            if (item.isDirectory) navigateInsideArchive(_state.value.archivePath!!, item.path)
            return
        }
        if (item.isArchive && archiveHelper.isArchive(item.path)) { navigateInsideArchive(item.path, ""); return }
        if (item.isDirectory) { navigateTo(item.path) } else {
            viewModelScope.launch {
                recentFileDao.upsert(RecentFileEntity(name = item.name, path = item.path, mimeType = item.mimeType, size = item.size))
                _events.emit(BrowserEvent.OpenFile(item))
            }
        }
    }

    fun onItemLongClick(item: FileItem) { toggleSelection(item.path) }

    fun applySwipe(item: FileItem, action: SwipeAction) {
        if (action == SwipeAction.NONE) return
        if (_state.value.insideArchive) {
            viewModelScope.launch { _events.emit(BrowserEvent.Toast("Gestures are unavailable inside archives")) }
            return
        }
        when (action) {
            SwipeAction.DELETE -> {
                if (_state.value.confirmDelete) {
                    _state.update { it.copy(deleteConfirmationItem = item) }
                } else {
                    _state.update { it.copy(selectedItems = setOf(item.path)) }
                    deleteSelected()
                }
            }
            SwipeAction.SHARE -> viewModelScope.launch {
                _events.emit(BrowserEvent.ShareFiles(listOf(item)))
            }
            SwipeAction.COMPRESS -> {
                _state.update { it.copy(selectedItems = setOf(item.path), showCompressDialog = true) }
            }
            SwipeAction.MOVE -> {
                _state.update {
                    it.copy(
                        clipboard = ClipboardContent(listOf(item), FileOperation.MOVE, it.currentPath),
                        selectedItems = emptySet(),
                    )
                }
                viewModelScope.launch { _events.emit(BrowserEvent.Toast("Move ready; navigate to a folder and tap Paste")) }
            }
            SwipeAction.NONE -> Unit
        }
    }

    fun dismissDeleteConfirmation() { _state.update { it.copy(deleteConfirmationItem = null) } }

    fun confirmDeleteGesture() {
        val item = _state.value.deleteConfirmationItem ?: return
        _state.update { it.copy(deleteConfirmationItem = null, selectedItems = setOf(item.path)) }
        deleteSelected()
    }

    fun extractArchive(archivePath: String? = null, destination: String? = null) {
        val archive = archivePath ?: _state.value.archivePath ?: return
        val dest = destination ?: _state.value.currentPath
        viewModelScope.launch {
            _events.emit(BrowserEvent.Toast("Extracting..."))
            archiveHelper.extract(archive, dest)
                .onSuccess { count -> _events.emit(BrowserEvent.Toast("Extracted $count items")) }
                .onFailure { e -> _events.emit(BrowserEvent.Toast("Extract failed: ${e.message}")) }
            if (!_state.value.insideArchive) refresh()
        }
    }

    fun compressSelected(outputName: String, format: ArchiveFormat = ArchiveFormat.ZIP, password: CharArray? = null) {
        val items = getSelectedFileItems()
        if (items.isEmpty()) return
        val outputPath = "${_state.value.currentPath}/$outputName.${format.extension}"
        viewModelScope.launch {
            _events.emit(BrowserEvent.Toast("Compressing..."))
            archiveHelper.createArchive(outputPath, items.map { it.path }, format, password)
                .onSuccess { _events.emit(BrowserEvent.Toast("Archive created")); clearSelection(); refresh() }
                .onFailure { e -> _events.emit(BrowserEvent.Toast("Compress failed: ${e.message}")) }
        }
    }

    fun watchSelectedIntegrity() {
        val paths = getSelectedFileItems().map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            var watched = 0
            var failed = 0
            paths.forEach { path ->
                integrityRepository.addPath(path)
                    .onSuccess { watched++ }
                    .onFailure { failed++ }
            }
            _events.emit(
                BrowserEvent.Toast(
                    if (failed == 0) "Watching $watched path(s) for changes"
                    else "Watching $watched path(s); $failed could not be added",
                ),
            )
            clearSelection()
        }
    }

    fun showTagDialog() {
        val paths = getSelectedFileItems().map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val commonTags = tagRepository.commonTagsForPaths(paths)
            _state.update {
                it.copy(showTagDialog = true, tagDialogSelectedTags = commonTags)
            }
        }
    }

    fun dismissTagDialog() {
        _state.update { it.copy(showTagDialog = false, tagDialogSelectedTags = emptySet()) }
    }

    fun toggleTagInDialog(tagName: String) {
        _state.update { state ->
            val selected = state.tagDialogSelectedTags.toMutableSet()
            if (!selected.add(tagName)) selected.remove(tagName)
            state.copy(tagDialogSelectedTags = selected)
        }
    }

    fun createTagForDialog(value: String) {
        viewModelScope.launch {
            tagRepository.createTag(value)
                .onSuccess { tag ->
                    _state.update { it.copy(tagDialogSelectedTags = it.tagDialogSelectedTags + tag.name) }
                }
                .onFailure { error -> _events.emit(BrowserEvent.Toast(error.message ?: "Unable to create tag")) }
        }
    }

    fun applyTags() {
        val paths = getSelectedFileItems().map { it.path }
        val selectedTags = _state.value.tagDialogSelectedTags
        if (paths.isEmpty()) {
            dismissTagDialog()
            return
        }
        viewModelScope.launch {
            tagRepository.replaceTags(paths, selectedTags)
            _events.emit(BrowserEvent.Toast("Applied ${selectedTags.size} tag(s) to ${paths.size} item(s)"))
            dismissTagDialog()
            clearSelection()
        }
    }

    fun showCompressDialog() { _state.update { it.copy(showCompressDialog = true) } }
    fun dismissCompressDialog() { _state.update { it.copy(showCompressDialog = false) } }

    fun toggleSelection(path: String) {
        _state.update { s -> val n = s.selectedItems.toMutableSet(); if (path in n) n.remove(path) else n.add(path); s.copy(selectedItems = n) }
    }
    fun selectAll() { _state.update { s -> s.copy(selectedItems = s.files.map { it.path }.toSet()) } }
    fun clearSelection() { _state.update { it.copy(selectedItems = emptySet()) } }

    fun copySelected() {
        val items = getSelectedFileItems()
        _state.update { it.copy(clipboard = ClipboardContent(items, FileOperation.COPY, it.currentPath), selectedItems = emptySet()) }
        viewModelScope.launch { _events.emit(BrowserEvent.Toast("${items.size} items copied")) }
    }

    fun cutSelected() {
        val items = getSelectedFileItems()
        _state.update { it.copy(clipboard = ClipboardContent(items, FileOperation.MOVE, it.currentPath), selectedItems = emptySet()) }
        viewModelScope.launch { _events.emit(BrowserEvent.Toast("${items.size} items cut")) }
    }

    fun paste() {
        val cb = _state.value.clipboard; if (cb.isEmpty) return
        transferQueueManager.enqueue(
            operation = if (cb.isCut) FileOperation.MOVE else FileOperation.COPY,
            sourcePaths = cb.items.map { it.path },
            destination = _state.value.currentPath,
        )
        _state.update { it.copy(clipboard = ClipboardContent()) }
        viewModelScope.launch {
            _events.emit(BrowserEvent.Toast("${cb.items.size} item(s) queued for ${if (cb.isCut) "move" else "copy"}"))
        }
    }

    fun deleteSelected() {
        deleteSelected(useTrash = true)
    }

    fun permanentlyDeleteSelected() {
        deleteSelected(useTrash = false)
    }

    private fun deleteSelected(useTrash: Boolean) {
        viewModelScope.launch {
            val paths = _state.value.selectedItems.toList()
            if (paths.isEmpty()) return@launch

            if (useTrash) {
                if (repoFactory.getRepository(paths.first()) !is LocalFileRepository) {
                    _events.emit(BrowserEvent.Toast("Trash is only available for local storage"))
                    return@launch
                }
                val roots = trashVolumeRoots()
                val ttlDays = settingsRepository.settings.first().trashTtlDays
                trashManager.purgeExpired(ttlDays, roots)
                    .onFailure { e -> _events.emit(BrowserEvent.Toast("Trash purge failed: ${e.message}")) }
                trashManager.moveToTrash(paths, roots)
                    .onSuccess { c -> _events.emit(BrowserEvent.Toast("$c items moved to Trash")); clearSelection(); refresh() }
                    .onFailure { e -> _events.emit(BrowserEvent.Toast("Trash failed: ${e.message}")) }
            } else {
                val repo = repoFactory.getRepository(paths.first())
                val secureEnabled = securityRepository.settings.first().secureDeleteEnabled
                if (secureEnabled && repo is LocalFileRepository) {
                    var failed = false
                    for (p in paths) {
                        SecureDelete.secureDelete(p).onFailure { e ->
                            _events.emit(BrowserEvent.Toast("Secure delete failed: ${e.message}"))
                            failed = true
                        }
                    }
                    if (!failed) _events.emit(BrowserEvent.Toast("${paths.size} items securely deleted"))
                    clearSelection(); refresh()
                } else {
                    if (secureEnabled && repo !is LocalFileRepository) {
                        _events.emit(BrowserEvent.Toast("Secure delete is only available for local files"))
                    }
                    repo.deleteFiles(paths)
                        .onSuccess { c -> _events.emit(BrowserEvent.Toast("$c items deleted permanently")); clearSelection(); refresh() }
                        .onFailure { e -> _events.emit(BrowserEvent.Toast("Delete failed: ${e.message}")) }
                }
            }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            repoFactory.getRepository(path).rename(path, newName)
                .onSuccess { _state.update { it.copy(renameItem = null) }; refresh() }
                .onFailure { e -> _events.emit(BrowserEvent.Toast("Rename failed: ${e.message}")) }
        }
    }

    fun showBatchRenameDialog() {
        if (_state.value.selectedItems.isNotEmpty()) {
            _state.update { it.copy(showBatchRenameDialog = true) }
        }
    }

    fun dismissBatchRenameDialog() {
        _state.update { it.copy(showBatchRenameDialog = false) }
    }

    fun batchRename(options: BatchRenameOptions) {
        val snapshot = _state.value
        val items = snapshot.files.filter { it.path in snapshot.selectedItems }
        val preview = BatchRenameEngine.preview(items, options)
        if (!preview.isValid) {
            viewModelScope.launch {
                _events.emit(
                    BrowserEvent.Toast(
                        preview.errors.firstOrNull() ?: "Choose a template that changes at least one name",
                    ),
                )
            }
            return
        }

        _state.update { it.copy(showBatchRenameDialog = false) }
        viewModelScope.launch {
            val staged = mutableListOf<StagedRename>()
            try {
                val repository = repoFactory.getRepository(snapshot.currentPath)
                val changedSources = preview.changedItems.map { it.item.path }.toSet()
                val conflict = preview.changedItems.firstOrNull { operation ->
                    operation.targetPath !in changedSources && repository.exists(operation.targetPath)
                }
                if (conflict != null) {
                    _events.emit(BrowserEvent.Toast("Target already exists: ${conflict.newName}"))
                    return@launch
                }

                val operationId = UUID.randomUUID().toString().replace("-", "")
                preview.changedItems.forEachIndexed { index, operation ->
                    val temporaryName = ".fileexplorer-rename-$operationId-$index"
                    val temporaryPath = BatchRenameEngine.siblingPath(operation.item.path, temporaryName)
                    if (repository.exists(temporaryPath)) {
                        error("Temporary rename target already exists")
                    }
                    repository.rename(operation.item.path, temporaryName).getOrThrow()
                    staged += StagedRename(operation, temporaryPath)
                }

                staged.forEach { operation ->
                    repository.rename(operation.temporaryPath, operation.preview.newName).getOrThrow()
                    operation.finalized = true
                }
                _events.emit(BrowserEvent.Toast("${staged.size} items renamed"))
                clearSelection()
                refresh()
            } catch (error: Exception) {
                if (staged.isNotEmpty()) {
                    val repository = repoFactory.getRepository(snapshot.currentPath)
                    staged.asReversed().forEach { operation ->
                        val currentPath = if (operation.finalized) {
                            operation.preview.targetPath
                        } else {
                            operation.temporaryPath
                        }
                        repository.rename(currentPath, operation.preview.item.name)
                    }
                }
                _events.emit(BrowserEvent.Toast("Batch rename failed: ${error.message}"))
                refresh()
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val path = "${_state.value.currentPath}/$name"
            repoFactory.getRepository(path).createDirectory(path)
                .onSuccess { _state.update { it.copy(showNewFolderDialog = false) }; refresh() }
                .onFailure { e -> _events.emit(BrowserEvent.Toast("Create failed: ${e.message}")) }
        }
    }

    fun showNewFolderDialog() { _state.update { it.copy(showNewFolderDialog = true) } }
    fun dismissNewFolderDialog() { _state.update { it.copy(showNewFolderDialog = false) } }
    fun showProperties(item: FileItem) { _state.update { it.copy(propertiesItem = item) } }
    fun dismissProperties() { _state.update { it.copy(propertiesItem = null) } }
    fun showRename(item: FileItem) { _state.update { it.copy(renameItem = item) } }
    fun dismissRename() { _state.update { it.copy(renameItem = null) } }
    fun shareSelected() { viewModelScope.launch { _events.emit(BrowserEvent.ShareFiles(getSelectedFileItems())) } }

    fun encryptSelected() {
        val paths = _state.value.selectedItems.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _events.emit(BrowserEvent.Toast("Encrypting..."))
            val result = fileEncryptionManager.encryptFiles(paths)
            _events.emit(BrowserEvent.Toast(encryptionSummary("Encrypted", result)))
            if (result.succeeded.isNotEmpty()) {
                clearSelection()
                refresh()
            }
        }
    }

    fun requestDecryptSelected() {
        val paths = _state.value.selectedItems.toList()
        if (paths.isNotEmpty()) {
            viewModelScope.launch { _events.emit(BrowserEvent.RequestDecrypt(paths)) }
        }
    }

    fun decryptFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _events.emit(BrowserEvent.Toast("Decrypting..."))
            val result = fileEncryptionManager.decryptFiles(paths)
            _events.emit(BrowserEvent.Toast(encryptionSummary("Decrypted", result)))
            if (result.succeeded.isNotEmpty()) {
                clearSelection()
                refresh()
            }
        }
    }

    private fun encryptionSummary(action: String, result: FileEncryptionBatchResult): String {
        return when {
            result.failures.isEmpty() -> "$action ${result.succeeded.size} file(s)"
            result.succeeded.isEmpty() -> "$action failed: ${result.failures.first()}"
            else -> "$action ${result.succeeded.size} file(s); ${result.failures.size} failed"
        }
    }

    fun setSortOrder(sortOrder: SortOrder) {
        val current = _state.value
        val updated = current.copy(
            sortOrder = sortOrder,
            files = sortFiles(current.files, sortOrder, current.showHidden),
            secondaryFiles = sortFiles(current.secondaryFiles, sortOrder, current.showHidden),
        )
        _state.value = updated
        persistDirectoryPreferences(updated)
    }

    fun toggleViewMode() {
        val current = _state.value
        val updated = current.copy(
            viewMode = if (current.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST,
        )
        _state.value = updated
        persistDirectoryPreferences(updated)
    }

    fun toggleColumn(column: FileColumn) {
        val current = _state.value
        val columns = current.visibleColumns.toMutableSet()
        if (!columns.add(column)) columns.remove(column)
        val updated = current.copy(visibleColumns = columns)
        _state.value = updated
        persistDirectoryPreferences(updated)
    }

    private fun persistDirectoryPreferences(state: BrowserUiState) {
        if (state.currentPath.isBlank() || state.insideArchive) return
        viewModelScope.launch {
            directoryViewPreferenceDao.upsert(
                DirectoryViewPreferenceCodec.encode(
                    path = state.currentPath,
                    viewMode = state.viewMode,
                    sortOrder = state.sortOrder,
                    visibleColumns = state.visibleColumns,
                ),
            )
        }
    }
    fun toggleHidden() {
        _state.update { state ->
            val showHidden = !state.showHidden
            state.copy(
                showHidden = showHidden,
                files = sortFiles(state.files, state.sortOrder, showHidden),
                secondaryFiles = sortFiles(state.secondaryFiles, state.sortOrder, showHidden),
            )
        }
        refresh()
        if (_state.value.dualPaneEnabled) refreshSecondary()
    }

    fun toggleBookmark(path: String, name: String) {
        viewModelScope.launch {
            if (bookmarkDao.exists(path)) { bookmarkDao.deleteByPath(path); _events.emit(BrowserEvent.Toast("Bookmark removed")) }
            else { bookmarkDao.insert(BookmarkEntity(name = name, path = path)); _events.emit(BrowserEvent.Toast("Bookmarked")) }
        }
    }

    fun refresh() {
        if (_state.value.insideArchive) navigateInsideArchive(_state.value.archivePath!!, _state.value.archiveInternalPath)
        else navigateTo(_state.value.currentPath)
    }

    private fun refreshPane(pane: BrowserPane) {
        if (pane == BrowserPane.PRIMARY) refresh() else refreshSecondary()
    }

    private fun clearPaneSelection(pane: BrowserPane) {
        _state.update {
            if (pane == BrowserPane.PRIMARY) it.copy(selectedItems = emptySet())
            else it.copy(secondarySelectedItems = emptySet())
        }
    }

    private fun updateTabPath(tabs: List<BrowserTab>, selectedIndex: Int, path: String): List<BrowserTab> {
        val tab = tabs.getOrNull(selectedIndex) ?: return tabs
        return tabs.toMutableList().apply { set(selectedIndex, tab.copy(path = path)) }
    }


    private fun loadVolumes() { _state.update { it.copy(volumes = storageVolumeHelper.getStorageVolumes()) } }
    private fun trashVolumeRoots(): List<String> = storageVolumeHelper.getStorageVolumes().map { it.path }.distinct()
    private fun observeBookmarks() { viewModelScope.launch { bookmarkDao.getAllFlow().collect { b -> _state.update { it.copy(bookmarks = b) } } } }
    private fun observeTags() { viewModelScope.launch { tagRepository.tags.collect { tags -> _state.update { it.copy(tags = tags) } } } }
    private fun getSelectedFileItems(): List<FileItem> { val s = _state.value.selectedItems; return _state.value.files.filter { it.path in s } }

    private data class StagedRename(
        val preview: com.explorer.fileexplorer.core.data.BatchRenamePreviewItem,
        val temporaryPath: String,
        var finalized: Boolean = false,
    )

    private fun sortFiles(files: List<FileItem>, order: SortOrder, showHidden: Boolean): List<FileItem> {
        val filtered = if (showHidden) files else files.filter { !it.isHidden }
        val cmp = compareBy<FileItem> { if (order.foldersFirst) if (it.isDirectory) 0 else 1 else 0 }
            .thenBy(when (order.field) {
                SortField.NAME -> compareBy<FileItem> { it.name.lowercase() }
                SortField.SIZE -> compareBy<FileItem> { it.size }
                SortField.DATE -> compareBy<FileItem> { it.lastModified }
                SortField.TYPE -> compareBy<FileItem> { it.extension.lowercase() }
            }.let { c -> if (order.direction == SortDirection.DESCENDING) c.reversed() else c }) { it }
        return filtered.sortedWith(cmp)
    }
}

private fun <T> Comparator<T>.thenBy(other: Comparator<T>, selector: (T) -> T): Comparator<T> {
    return Comparator { a, b -> val r = this.compare(a, b); if (r != 0) r else other.compare(selector(a), selector(b)) }
}
