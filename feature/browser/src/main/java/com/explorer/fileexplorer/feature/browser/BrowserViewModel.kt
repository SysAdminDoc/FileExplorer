package com.explorer.fileexplorer.feature.browser

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.data.ArchiveHelper
import com.explorer.fileexplorer.core.data.FileRepository
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.data.RootFileRepository
import com.explorer.fileexplorer.core.database.BookmarkDao
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.RecentFileDao
import com.explorer.fileexplorer.core.database.RecentFileEntity
import com.explorer.fileexplorer.core.model.*
import com.explorer.fileexplorer.core.storage.RootHelper
import com.explorer.fileexplorer.core.storage.RootState
import com.explorer.fileexplorer.core.storage.StorageVolumeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserUiState(
    val currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortOrder: SortOrder = SortOrder(),
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    val selectedItems: Set<String> = emptySet(),
    val clipboard: ClipboardContent = ClipboardContent(),
    val volumes: List<StorageVolume> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val pathHistory: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val propertiesItem: FileItem? = null,
    val renameItem: FileItem? = null,
    val showNewFolderDialog: Boolean = false,
    val rootState: RootState = RootState.UNKNOWN,
    val rootEnabled: Boolean = false,
    val selinuxContext: String? = null,
    val insideArchive: Boolean = false,
    val archivePath: String? = null,
    val archiveInternalPath: String = "",
    val showCompressDialog: Boolean = false,
    val showExtractDialog: Boolean = false,
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

sealed interface BrowserEvent {
    data class Toast(val message: String) : BrowserEvent
    data class OpenFile(val item: FileItem) : BrowserEvent
    data class ShareFiles(val items: List<FileItem>) : BrowserEvent
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val repoFactory: FileRepositoryFactory,
    private val rootHelper: RootHelper,
    private val rootRepo: RootFileRepository,
    private val archiveHelper: ArchiveHelper,
    private val storageVolumeHelper: StorageVolumeHelper,
    private val bookmarkDao: BookmarkDao,
    private val recentFileDao: RecentFileDao,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BrowserEvent>()
    val events: SharedFlow<BrowserEvent> = _events.asSharedFlow()

    init {
        loadVolumes()
        observeBookmarks()
        observeRootState()
        initializeRoot()
        navigateTo(_state.value.currentPath)
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
            _state.update { it.copy(isLoading = true, error = null, selectedItems = emptySet()) }
            val repo = repoFactory.getRepository(path)
            repo.listFiles(path).collect { files ->
                val sorted = sortFiles(files, _state.value.sortOrder, _state.value.showHidden)
                val history = _state.value.pathHistory.toMutableList()
                val idx = _state.value.historyIndex
                if (idx < history.lastIndex) { while (history.size > idx + 1) history.removeLast() }
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
            _state.update { it.copy(isLoading = true, selectedItems = emptySet()) }
            repoFactory.getRepository(path).listFiles(path).collect { files ->
                _state.update { it.copy(currentPath = path, files = sortFiles(files, it.sortOrder, it.showHidden),
                    isLoading = false, historyIndex = historyIndex) }
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
        viewModelScope.launch {
            val repo = repoFactory.getRepository(_state.value.currentPath)
            val r = when (cb.operation) {
                FileOperation.COPY -> repo.copyFiles(cb.items.map { it.path }, _state.value.currentPath, ConflictResolution.RENAME)
                FileOperation.MOVE -> repo.moveFiles(cb.items.map { it.path }, _state.value.currentPath, ConflictResolution.RENAME)
                else -> return@launch
            }
            r.onSuccess { c -> _events.emit(BrowserEvent.Toast("$c items ${if (cb.isCut) "moved" else "copied"}")); _state.update { it.copy(clipboard = ClipboardContent()) }; refresh() }
             .onFailure { e -> _events.emit(BrowserEvent.Toast("Error: ${e.message}")) }
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val paths = _state.value.selectedItems.toList()
            repoFactory.getRepository(paths.firstOrNull() ?: return@launch).deleteFiles(paths)
                .onSuccess { c -> _events.emit(BrowserEvent.Toast("$c items deleted")); clearSelection(); refresh() }
                .onFailure { e -> _events.emit(BrowserEvent.Toast("Delete failed: ${e.message}")) }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            repoFactory.getRepository(path).rename(path, newName)
                .onSuccess { _state.update { it.copy(renameItem = null) }; refresh() }
                .onFailure { e -> _events.emit(BrowserEvent.Toast("Rename failed: ${e.message}")) }
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

    fun setSortOrder(sortOrder: SortOrder) {
        _state.update { s -> s.copy(sortOrder = sortOrder, files = sortFiles(s.files, sortOrder, s.showHidden)) }
    }
    fun toggleViewMode() { _state.update { s -> s.copy(viewMode = if (s.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) } }
    fun toggleHidden() { _state.update { it.copy(showHidden = !it.showHidden) }; refresh() }

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

    private fun loadVolumes() { _state.update { it.copy(volumes = storageVolumeHelper.getStorageVolumes()) } }
    private fun observeBookmarks() { viewModelScope.launch { bookmarkDao.getAllFlow().collect { b -> _state.update { it.copy(bookmarks = b) } } } }
    private fun getSelectedFileItems(): List<FileItem> { val s = _state.value.selectedItems; return _state.value.files.filter { it.path in s } }

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
