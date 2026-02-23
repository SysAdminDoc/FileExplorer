package com.explorer.fileexplorer.feature.cloud

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.cloud.CloudAccount
import com.explorer.fileexplorer.core.cloud.CloudAccountManager
import com.explorer.fileexplorer.core.cloud.CloudService
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.ui.FileListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudUiState(
    val accounts: List<CloudAccount> = emptyList(),
    val browsingAccount: CloudAccount? = null,
    val currentFolderId: String = "root",
    val folderStack: List<Pair<String, String>> = emptyList(), // (id, name) pairs
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val showAddDialog: Boolean = false,
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    private val accountManager: CloudAccountManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CloudUiState())
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>()
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            accountManager.accounts.collect { accounts ->
                _state.update { it.copy(accounts = accounts) }
            }
        }
    }

    fun browseAccount(account: CloudAccount, folderId: String = "root") {
        val provider = accountManager.getProvider(account.service) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, browsingAccount = account, currentFolderId = folderId) }
            provider.listFiles(account, folderId).collect { files ->
                val sorted = files.sortedWith(compareBy<FileItem> { if (it.isDirectory) 0 else 1 }.thenBy { it.name.lowercase() })
                _state.update { it.copy(files = sorted, isLoading = false) }
            }
        }
    }

    fun navigateToFolder(item: FileItem) {
        val account = _state.value.browsingAccount ?: return
        _state.update { it.copy(folderStack = it.folderStack + (it.currentFolderId to "..")) }
        browseAccount(account, item.path)
    }

    fun navigateUp() {
        val stack = _state.value.folderStack
        if (stack.isEmpty()) { closeBrowser(); return }
        val (parentId, _) = stack.last()
        val account = _state.value.browsingAccount ?: return
        _state.update { it.copy(folderStack = stack.dropLast(1)) }
        browseAccount(account, parentId)
    }

    fun closeBrowser() {
        _state.update { it.copy(browsingAccount = null, files = emptyList(), folderStack = emptyList(), currentFolderId = "root") }
    }

    fun deleteCloudItem(item: FileItem) {
        val account = _state.value.browsingAccount ?: return
        val provider = accountManager.getProvider(account.service) ?: return
        viewModelScope.launch {
            provider.delete(account, item.path)
                .onSuccess { _toasts.emit("Deleted ${item.name}"); browseAccount(account, _state.value.currentFolderId) }
                .onFailure { e -> _toasts.emit("Delete failed: ${e.message}") }
        }
    }

    fun createCloudFolder(name: String) {
        val account = _state.value.browsingAccount ?: return
        val provider = accountManager.getProvider(account.service) ?: return
        viewModelScope.launch {
            provider.createFolder(account, name, _state.value.currentFolderId)
                .onSuccess { _toasts.emit("Folder created"); browseAccount(account, _state.value.currentFolderId) }
                .onFailure { e -> _toasts.emit("Create failed: ${e.message}") }
        }
    }

    fun removeAccount(account: CloudAccount) {
        val provider = accountManager.getProvider(account.service)
        viewModelScope.launch {
            provider?.signOut(account)
            accountManager.removeAccount(account.id)
            _toasts.emit("Account removed")
        }
    }

    fun showAddDialog() { _state.update { it.copy(showAddDialog = true) } }
    fun hideAddDialog() { _state.update { it.copy(showAddDialog = false) } }

    fun addAccount(service: CloudService) {
        val provider = accountManager.getProvider(service)
        if (provider == null) {
            viewModelScope.launch { _toasts.emit("${service.displayName} requires OAuth configuration") }
            return
        }
        viewModelScope.launch {
            val intent = provider.getAuthIntent()
            if (intent == null) { _toasts.emit("${service.displayName} OAuth not configured yet") }
            // In production: launch intent via Activity result launcher
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(
    viewModel: CloudViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    // Browsing cloud files
    if (state.browsingAccount != null) {
        CloudBrowserScreen(
            account = state.browsingAccount!!,
            files = state.files, isLoading = state.isLoading,
            folderDepth = state.folderStack.size,
            onItemClick = { item -> if (item.isDirectory) viewModel.navigateToFolder(item) },
            onNavigateUp = viewModel::navigateUp,
            onClose = viewModel::closeBrowser,
            onCreateFolder = viewModel::createCloudFolder,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud Storage") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = viewModel::showAddDialog) { Icon(Icons.Filled.Add, "Add Account") } },
            )
        },
    ) { padding ->
        if (state.accounts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CloudQueue, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text("No cloud accounts", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = viewModel::showAddDialog) { Text("Add Account") }
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(state.accounts, key = { it.id }) { account ->
                    CloudAccountItem(
                        account = account,
                        onBrowse = { viewModel.browseAccount(account) },
                        onRemove = { viewModel.removeAccount(account) },
                    )
                }
            }
        }
    }

    if (state.showAddDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddDialog,
            title = { Text("Add Cloud Account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CloudService.entries.forEach { service ->
                        val icon = when (service) {
                            CloudService.GOOGLE_DRIVE -> Icons.Filled.CloudCircle
                            CloudService.DROPBOX -> Icons.Filled.CloudUpload
                            CloudService.ONEDRIVE -> Icons.Filled.Cloud
                        }
                        ListItem(
                            headlineContent = { Text(service.displayName) },
                            leadingContent = { Icon(icon, null) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(
                            onClick = { viewModel.addAccount(service); viewModel.hideAddDialog() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) { Text("Connect ${service.displayName}") }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::hideAddDialog) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CloudAccountItem(account: CloudAccount, onBrowse: () -> Unit, onRemove: () -> Unit) {
    val icon = when (account.service) {
        CloudService.GOOGLE_DRIVE -> Icons.Filled.CloudCircle
        CloudService.DROPBOX -> Icons.Filled.CloudUpload
        CloudService.ONEDRIVE -> Icons.Filled.Cloud
    }
    ListItem(
        headlineContent = { Text(account.displayName.ifEmpty { account.email }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("${account.service.displayName} - ${account.email}", style = MaterialTheme.typography.bodySmall)
                if (account.quotaTotal > 0) {
                    val usedGb = "%.1f".format(account.quotaUsed / (1024.0 * 1024 * 1024))
                    val totalGb = "%.1f".format(account.quotaTotal / (1024.0 * 1024 * 1024))
                    Text("$usedGb / $totalGb GB", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(
                        progress = { (account.quotaUsed.toFloat() / account.quotaTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                    )
                }
            }
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Row {
                IconButton(onClick = onBrowse) { Icon(Icons.Filled.FolderOpen, "Browse") }
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { onRemove(); expanded = false },
                        leadingIcon = { Icon(Icons.Filled.RemoveCircle, null) })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudBrowserScreen(
    account: CloudAccount,
    files: List<FileItem>,
    isLoading: Boolean,
    folderDepth: Int,
    onItemClick: (FileItem) -> Unit,
    onNavigateUp: () -> Unit,
    onClose: () -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var showNewFolder by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(account.displayName.ifEmpty { account.service.displayName },
                            maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(account.email, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (folderDepth > 0) { IconButton(onClick = onNavigateUp) { Icon(Icons.Filled.ArrowUpward, "Up") } }
                    IconButton(onClick = { showNewFolder = true }) { Icon(Icons.Filled.CreateNewFolder, "New Folder") }
                },
            )
        },
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(files, key = { it.path }) { item ->
                    FileListItem(item = item, onClick = { onItemClick(item) }, onLongClick = {})
                }
            }
        }
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showNewFolder = false }, title = { Text("New Folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onCreateFolder(name.trim()); showNewFolder = false } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } })
    }
}
