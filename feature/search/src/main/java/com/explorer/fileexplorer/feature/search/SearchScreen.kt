package com.explorer.fileexplorer.feature.search

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.DiagnosticLog
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.data.TagRepository
import com.explorer.fileexplorer.core.database.SearchHistoryDao
import com.explorer.fileexplorer.core.database.SearchHistoryEntity
import com.explorer.fileexplorer.core.database.SavedSearchDao
import com.explorer.fileexplorer.core.database.SavedSearchEntity
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import com.explorer.fileexplorer.core.database.TagEntity
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.CapabilityStatus
import com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix
import com.explorer.fileexplorer.core.model.RepositoryFeature
import com.explorer.fileexplorer.core.ui.FileListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<FileItem> = emptyList(),
    val isSearching: Boolean = false,
    val searchPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val useRegex: Boolean = false,
    val includeHidden: Boolean = false,
    val history: List<SearchHistoryEntity> = emptyList(),
    val availableTags: List<TagEntity> = emptyList(),
    val selectedTags: Set<String> = emptySet(),
    val capabilityMatrix: RepositoryCapabilityMatrix = RepositoryCapabilityMatrix.unavailable(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repositoryFactory: FileRepositoryFactory,
    private val diagnosticLog: DiagnosticLog,
    private val searchHistoryDao: SearchHistoryDao,
    private val savedSearchDao: SavedSearchDao,
    private val tagRepository: TagRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            searchHistoryDao.getHistoryFlow().collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            tagRepository.tags.collect { tags ->
                _state.update { it.copy(availableTags = tags) }
            }
        }
    }

    fun updateQuery(query: String) { _state.update { it.copy(query = query) } }
    fun setSearchPath(path: String) {
        val repository = repositoryFactory.getRepository(path)
        val matrix = repository.capabilityMatrix(path)
        diagnosticLog.logCapabilities(matrix)
        _state.update { it.copy(searchPath = path, capabilityMatrix = matrix) }
    }
    fun applySavedSearch(query: String, path: String, useRegex: Boolean) {
        _state.update { it.copy(query = query, searchPath = path, useRegex = useRegex) }
        search()
    }
    fun toggleRegex() { _state.update { it.copy(useRegex = !it.useRegex) } }
    fun toggleHidden() { _state.update { it.copy(includeHidden = !it.includeHidden) } }
    fun toggleTag(tagName: String) {
        _state.update { state ->
            val selected = state.selectedTags.toMutableSet()
            if (!selected.add(tagName)) selected.remove(tagName)
            state.copy(selectedTags = selected)
        }
    }
    fun clearTagFilters() { _state.update { it.copy(selectedTags = emptySet()) } }

    fun search() {
        val query = _state.value.query.trim()
        val selectedTags = _state.value.selectedTags
        if (query.isEmpty() && selectedTags.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val path = _state.value.searchPath
            val repository = repositoryFactory.getRepository(path)
            val matrix = repository.capabilityMatrix(path)
            diagnosticLog.logCapabilities(matrix)
            _state.update { it.copy(capabilityMatrix = matrix) }
            if (!matrix.isActionEnabled(RepositoryFeature.SEARCH)) return@launch
            _state.update { it.copy(isSearching = true, results = emptyList()) }

            // Save to history
            if (query.isNotEmpty()) {
                searchHistoryDao.upsert(SearchHistoryEntity(query = query, scopePath = _state.value.searchPath))
            }
            val taggedPaths = selectedTags.takeIf { it.isNotEmpty() }
                ?.let { tagRepository.pathsForAllTags(it) }

            repository.search(
                rootPath = path,
                query = query,
                regex = _state.value.useRegex,
                includeHidden = _state.value.includeHidden,
            ).collect { item ->
                val canonicalPath = runCatching { File(item.path).canonicalPath }.getOrNull()
                if (taggedPaths == null || item.path in taggedPaths || canonicalPath in taggedPaths) {
                    _state.update { it.copy(results = it.results + item) }
                }
            }

            _state.update { it.copy(isSearching = false) }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _state.update { it.copy(isSearching = false) }
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryDao.clearAll() }
    }

    fun saveSearch(name: String) {
        val current = _state.value
        val cleanName = name.trim()
        val query = current.query.trim()
        if (cleanName.isEmpty() || query.isEmpty()) return
        viewModelScope.launch {
            savedSearchDao.upsert(
                SavedSearchEntity(
                    name = cleanName,
                    query = query,
                    scopePath = current.searchPath,
                    useRegex = current.useRegex,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
    initialQuery: String? = null,
    initialUseRegex: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onOpenFile: (FileItem) -> Unit = {},
    onNavigateToFolder: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedSearchName by remember { mutableStateOf("") }

    LaunchedEffect(currentPath, initialQuery, initialUseRegex) {
        if (initialQuery != null) viewModel.applySavedSearch(initialQuery, currentPath, initialUseRegex)
        else viewModel.setSearchPath(currentPath)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        placeholder = { Text(stringResource(DesignSystemR.string.search_files_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelSearch()
                        onNavigateBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back)) }
                },
                actions = {
                    IconButton(
                        enabled = state.query.isNotBlank(),
                        onClick = { showSaveDialog = true },
                    ) { Icon(Icons.Filled.BookmarkAdd, stringResource(DesignSystemR.string.save_search)) }
                    IconButton(
                        enabled = (state.query.isNotBlank() || state.selectedTags.isNotEmpty()) &&
                            state.capabilityMatrix.isActionEnabled(RepositoryFeature.SEARCH),
                        onClick = viewModel::search,
                    ) { Icon(Icons.Filled.Search, stringResource(DesignSystemR.string.search)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Options row
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.useRegex,
                    onClick = viewModel::toggleRegex,
                    label = { Text(stringResource(DesignSystemR.string.regex)) },
                )
                FilterChip(
                    selected = state.includeHidden,
                    onClick = viewModel::toggleHidden,
                    label = { Text(stringResource(DesignSystemR.string.hidden)) },
                )
                Spacer(Modifier.weight(1f))
                if (state.isSearching) {
                    Text(stringResource(DesignSystemR.string.found_count, state.results.size), style = MaterialTheme.typography.labelMedium)
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (state.results.isNotEmpty()) {
                    Text(stringResource(DesignSystemR.string.results_count, state.results.size), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (state.capabilityMatrix.featureStatus(RepositoryFeature.SEARCH).status != CapabilityStatus.VERIFIED) {
                Text(
                    text = state.capabilityMatrix.explanation(RepositoryFeature.SEARCH),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.availableTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(DesignSystemR.string.tags), style = MaterialTheme.typography.labelMedium)
                    state.availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag.name in state.selectedTags,
                            onClick = { viewModel.toggleTag(tag.name) },
                            label = { Text("#${tag.name}") },
                        )
                    }
                    if (state.selectedTags.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearTagFilters) { Text(stringResource(DesignSystemR.string.clear)) }
                    }
                }
            }

            // Results
            if (state.results.isEmpty() && !state.isSearching && state.query.isEmpty() && state.selectedTags.isEmpty()) {
                // Show history
                if (state.history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(DesignSystemR.string.recent_searches), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearHistory) { Text(stringResource(DesignSystemR.string.clear)) }
                    }
                    LazyColumn {
                        items(state.history) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.query) },
                                leadingContent = { Icon(Icons.Filled.History, null) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.results, key = { it.path }) { item ->
                        FileListItem(
                            item = item,
                            onClick = {
                                if (item.isDirectory) onNavigateToFolder(item.path)
                                else onOpenFile(item)
                            },
                            onLongClick = { /* properties */ },
                        )
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(DesignSystemR.string.save_search)) },
            text = {
                OutlinedTextField(
                    value = savedSearchName,
                    onValueChange = { savedSearchName = it },
                    label = { Text(stringResource(DesignSystemR.string.saved_search_name)) },
                    placeholder = { Text(stringResource(DesignSystemR.string.saved_search_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = savedSearchName.isNotBlank() && state.query.isNotBlank(),
                    onClick = {
                        viewModel.saveSearch(savedSearchName)
                        savedSearchName = ""
                        showSaveDialog = false
                    },
                ) { Text(stringResource(DesignSystemR.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(DesignSystemR.string.cancel))
                }
            },
        )
    }
}
