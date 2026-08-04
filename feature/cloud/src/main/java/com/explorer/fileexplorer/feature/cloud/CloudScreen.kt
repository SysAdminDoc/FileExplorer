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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.cloud.CloudAccount
import com.explorer.fileexplorer.core.cloud.CloudAccountManager
import com.explorer.fileexplorer.core.cloud.CloudService
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
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
                .onSuccess { _toasts.emit("Account removed") }
                .onFailure { e -> _toasts.emit("Remove failed: ${e.message}") }
        }
    }

    fun setStaySignedIn(account: CloudAccount, enabled: Boolean) {
        viewModelScope.launch {
            accountManager.setStaySignedIn(account.id, enabled)
                .onSuccess {
                    _toasts.emit(if (enabled) "Account will stay signed in" else "Account will be forgotten on app close")
                }
                .onFailure { e -> _toasts.emit("Account storage failed: ${e.message}") }
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
                title = { Text(stringResource(DesignSystemR.string.cloud_storage)) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back)) } },
                actions = { IconButton(onClick = viewModel::showAddDialog) { Icon(Icons.Filled.Add, stringResource(DesignSystemR.string.add_account)) } },
            )
        },
    ) { padding ->
        if (state.accounts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CloudQueue, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(DesignSystemR.string.no_cloud_accounts), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = viewModel::showAddDialog) { Text(stringResource(DesignSystemR.string.add_account)) }
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(state.accounts, key = { it.id }) { account ->
                    CloudAccountItem(
                        account = account,
                        onBrowse = { viewModel.browseAccount(account) },
                        onRemove = { viewModel.removeAccount(account) },
                        onStaySignedInChange = { enabled -> viewModel.setStaySignedIn(account, enabled) },
                    )
                }
            }
        }
    }

    if (state.showAddDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddDialog,
            title = { Text(stringResource(DesignSystemR.string.add_cloud_account)) },
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
                        ) { Text("${stringResource(DesignSystemR.string.connect)} ${service.displayName}") }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::hideAddDialog) { Text(stringResource(DesignSystemR.string.cancel)) } },
        )
    }
}

@Composable
private fun CloudAccountItem(
    account: CloudAccount,
    onBrowse: () -> Unit,
    onRemove: () -> Unit,
    onStaySignedInChange: (Boolean) -> Unit,
) {
    val icon = when (account.service) {
        CloudService.GOOGLE_DRIVE -> Icons.Filled.CloudCircle
        CloudService.DROPBOX -> Icons.Filled.CloudUpload
        CloudService.ONEDRIVE -> Icons.Filled.Cloud
    }
    ListItem(
        headlineContent = { Text(account.displayName.ifEmpty { account.email }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(stringResource(DesignSystemR.string.cloud_account_identity, account.service.displayName, account.email), style = MaterialTheme.typography.bodySmall)
                if (account.staySignedIn) {
                    Text(stringResource(DesignSystemR.string.stays_signed_in_device), style = MaterialTheme.typography.labelSmall)
                }
                if (account.quotaTotal > 0) {
                    val usedGb = "%.1f".format(account.quotaUsed / (1024.0 * 1024 * 1024))
                    val totalGb = "%.1f".format(account.quotaTotal / (1024.0 * 1024 * 1024))
                    Text(stringResource(DesignSystemR.string.cloud_quota, usedGb, totalGb), style = MaterialTheme.typography.labelSmall)
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
                IconButton(onClick = onBrowse) { Icon(Icons.Filled.FolderOpen, stringResource(DesignSystemR.string.browse)) }
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, stringResource(DesignSystemR.string.more)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (account.staySignedIn) DesignSystemR.string.forget_on_close else DesignSystemR.string.stay_signed_in))
                        },
                        onClick = {
                            onStaySignedInChange(!account.staySignedIn)
                            expanded = false
                        },
                        leadingIcon = { Icon(if (account.staySignedIn) Icons.Filled.LockOpen else Icons.Filled.Lock, null) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(DesignSystemR.string.remove)) }, onClick = { onRemove(); expanded = false },
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
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back)) } },
                actions = {
                    if (folderDepth > 0) { IconButton(onClick = onNavigateUp) { Icon(Icons.Filled.ArrowUpward, stringResource(DesignSystemR.string.back)) } }
                    IconButton(onClick = { showNewFolder = true }) { Icon(Icons.Filled.CreateNewFolder, stringResource(DesignSystemR.string.new_folder)) }
                },
            )
        },
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(DesignSystemR.string.empty_directory), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        AlertDialog(onDismissRequest = { showNewFolder = false }, title = { Text(stringResource(DesignSystemR.string.new_folder)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(DesignSystemR.string.folder_name)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onCreateFolder(name.trim()); showNewFolder = false } }) { Text(stringResource(DesignSystemR.string.create)) } },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text(stringResource(DesignSystemR.string.cancel)) } })
    }
}
