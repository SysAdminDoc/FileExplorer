package com.explorer.fileexplorer.feature.browser

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.RootModule
import com.explorer.fileexplorer.core.data.RootModuleRepository
import com.explorer.fileexplorer.core.data.RootModuleSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class RootModulesUiState(
    val managerName: String = "Root manager",
    val modules: List<RootModule> = emptyList(),
    val isLoading: Boolean = true,
    val isInstalling: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class RootModulesViewModel @Inject constructor(
    private val repository: RootModuleRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(RootModulesUiState())
    val state: StateFlow<RootModulesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.listModules()
                .onSuccess { snapshot -> _state.updateFrom(snapshot) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Unable to load root modules",
                        )
                    }
                }
        }
    }

    fun setEnabled(module: RootModule, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(module, enabled)
                .onSuccess {
                    _state.update { it.copy(message = if (enabled) "${module.name} enabled" else "${module.name} disabled") }
                    refresh()
                }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Unable to change module state") } }
        }
    }

    fun installFromUri(uri: Uri) {
        if (_state.value.isInstalling) return
        viewModelScope.launch {
            _state.update { it.copy(isInstalling = true, error = null) }
            val temporaryFile = runCatching { copyUriToCache(uri) }
                .getOrElse { error ->
                    _state.update {
                        it.copy(isInstalling = false, error = error.message ?: "Unable to read selected ZIP")
                    }
                    return@launch
                }
            try {
                repository.installModule(temporaryFile.absolutePath)
                    .onSuccess { message ->
                        _state.update { it.copy(message = message) }
                        refresh()
                    }
                    .onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Module installation failed") }
                    }
            } finally {
                temporaryFile.delete()
                _state.update { it.copy(isInstalling = false) }
            }
        }
    }

    fun clearMessage() { _state.update { it.copy(message = null) } }

    private suspend fun copyUriToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val target = File.createTempFile("file-explorer-module-", ".zip", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Selected document cannot be opened")
            input.use { source -> target.outputStream().use { destination -> source.copyTo(destination) } }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun MutableStateFlow<RootModulesUiState>.updateFrom(snapshot: RootModuleSnapshot) {
        update {
            it.copy(
                managerName = snapshot.manager.displayName,
                modules = snapshot.modules,
                isLoading = false,
                error = null,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RootModulesScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: RootModulesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installFromUri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Root Modules") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading && !state.isInstalling) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh modules")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Install ZIP") },
                icon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                onClick = {
                    if (!state.isLoading && !state.isInstalling) {
                        picker.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.isInstalling) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            RootModulesHeader(
                managerName = state.managerName,
                moduleCount = state.modules.size,
                isLoading = state.isLoading,
                error = state.error,
            )
            if (state.isLoading) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Button(onClick = viewModel::refresh, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Retry")
                }
            } else if (state.modules.isEmpty()) {
                EmptyRootModules()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.modules, key = { it.id }) { module ->
                        RootModuleCard(module = module, onEnabledChange = { enabled -> viewModel.setEnabled(module, enabled) })
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RootModulesHeader(
    managerName: String,
    moduleCount: Int,
    isLoading: Boolean,
    error: String?,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(managerName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (!isLoading) Text("$moduleCount", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Install trusted module ZIPs, then enable or disable installed modules. Changes commonly apply after reboot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun EmptyRootModules() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No installed modules", style = MaterialTheme.typography.titleMedium)
        Text(
            "Choose Install ZIP to install a module through the detected root manager.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RootModuleCard(module: RootModule, onEnabledChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = {
                    Text(module.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        buildString {
                            append(module.version)
                            if (module.author.isNotBlank()) append(" · ${module.author}")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Filled.Extension, contentDescription = null) },
                trailingContent = {
                    Switch(checked = module.enabled, onCheckedChange = onEnabledChange)
                },
            )
            if (module.description.isNotBlank()) {
                Text(
                    module.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (module.pendingRemoval || module.skipMount) {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (module.pendingRemoval) {
                        Text("Pending removal after reboot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (module.skipMount) {
                        if (module.pendingRemoval) Spacer(Modifier.width(12.dp))
                        Text("Mount skipped", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
