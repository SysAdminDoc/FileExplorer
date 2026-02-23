package com.explorer.fileexplorer.feature.search

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.LocalFileRepository
import com.explorer.fileexplorer.core.database.SearchHistoryDao
import com.explorer.fileexplorer.core.database.SearchHistoryEntity
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.ui.FileListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<FileItem> = emptyList(),
    val isSearching: Boolean = false,
    val searchPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val useRegex: Boolean = false,
    val includeHidden: Boolean = false,
    val history: List<SearchHistoryEntity> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val fileRepository: LocalFileRepository,
    private val searchHistoryDao: SearchHistoryDao,
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
    }

    fun updateQuery(query: String) { _state.update { it.copy(query = query) } }
    fun setSearchPath(path: String) { _state.update { it.copy(searchPath = path) } }
    fun toggleRegex() { _state.update { it.copy(useRegex = !it.useRegex) } }
    fun toggleHidden() { _state.update { it.copy(includeHidden = !it.includeHidden) } }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isSearching = true, results = emptyList()) }

            // Save to history
            searchHistoryDao.upsert(SearchHistoryEntity(query = query, scopePath = _state.value.searchPath))

            fileRepository.searchStreaming(
                rootPath = _state.value.searchPath,
                query = query,
                regex = _state.value.useRegex,
                includeHidden = _state.value.includeHidden,
            ).collect { item ->
                _state.update { it.copy(results = it.results + item) }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
    onNavigateBack: () -> Unit = {},
    onOpenFile: (FileItem) -> Unit = {},
    onNavigateToFolder: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentPath) { viewModel.setSearchPath(currentPath) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        placeholder = { Text("Search files...") },
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
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::search) { Icon(Icons.Filled.Search, "Search") }
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
                    label = { Text("Regex") },
                )
                FilterChip(
                    selected = state.includeHidden,
                    onClick = viewModel::toggleHidden,
                    label = { Text("Hidden") },
                )
                Spacer(Modifier.weight(1f))
                if (state.isSearching) {
                    Text("${state.results.size} found...", style = MaterialTheme.typography.labelMedium)
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (state.results.isNotEmpty()) {
                    Text("${state.results.size} results", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Results
            if (state.results.isEmpty() && !state.isSearching && state.query.isEmpty()) {
                // Show history
                if (state.history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent searches", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearHistory) { Text("Clear") }
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
}
