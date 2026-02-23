package com.explorer.fileexplorer.feature.network

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.database.ConnectionDao
import com.explorer.fileexplorer.core.database.ConnectionEntity
import com.explorer.fileexplorer.core.designsystem.*
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.network.ConnectionManager
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import com.explorer.fileexplorer.core.network.Protocol
import com.explorer.fileexplorer.core.ui.FileListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// -- ViewModel --

data class NetworkUiState(
    val connections: List<ConnectionEntity> = emptyList(),
    val activeConnectionIds: Set<Long> = emptySet(),
    val isConnecting: Long? = null, // ID of connection being connected
    val showForm: Boolean = false,
    val editingConnection: ConnectionEntity? = null,
    // Remote browser state
    val browsingConnectionId: Long? = null,
    val remotePath: String = "/",
    val remoteFiles: List<FileItem> = emptyList(),
    val isLoadingRemote: Boolean = false,
    val remoteError: String? = null,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val connectionDao: ConnectionDao,
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>()
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            connectionManager.savedConnections.collect { connections ->
                _state.update { it.copy(connections = connections) }
            }
        }
        viewModelScope.launch {
            connectionManager.activeConnections.collect { active ->
                _state.update { it.copy(activeConnectionIds = active.keys) }
            }
        }
    }

    fun connect(entity: ConnectionEntity) {
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = entity.id) }
            val result = connectionManager.connect(entity)
            _state.update { it.copy(isConnecting = null) }
            result.onSuccess {
                _toasts.emit("Connected to ${entity.name}")
                browseRemote(entity.id, entity.remotePath.ifEmpty { "/" })
            }.onFailure { e ->
                _toasts.emit("Connection failed: ${e.message}")
            }
        }
    }

    fun disconnect(connectionId: Long) {
        viewModelScope.launch {
            connectionManager.disconnect(connectionId)
            if (_state.value.browsingConnectionId == connectionId) {
                _state.update { it.copy(browsingConnectionId = null, remoteFiles = emptyList()) }
            }
            _toasts.emit("Disconnected")
        }
    }

    fun browseRemote(connectionId: Long, path: String) {
        val repo = connectionManager.getActiveRepo(connectionId) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingRemote = true, browsingConnectionId = connectionId, remotePath = path, remoteError = null) }
            repo.listFiles(path).collect { files ->
                val sorted = files.sortedWith(compareBy<FileItem> { if (it.isDirectory) 0 else 1 }.thenBy { it.name.lowercase() })
                _state.update { it.copy(remoteFiles = sorted, isLoadingRemote = false) }
            }
        }
    }

    fun navigateRemoteUp() {
        val current = _state.value.remotePath
        val parent = current.trimEnd('/').substringBeforeLast('/', "/")
        val connId = _state.value.browsingConnectionId ?: return
        browseRemote(connId, parent)
    }

    fun onRemoteItemClick(item: FileItem) {
        if (item.isDirectory) {
            val connId = _state.value.browsingConnectionId ?: return
            browseRemote(connId, item.path)
        }
    }

    fun deleteRemoteFiles(paths: List<String>) {
        val connId = _state.value.browsingConnectionId ?: return
        val repo = connectionManager.getActiveRepo(connId) ?: return
        viewModelScope.launch {
            repo.deleteFiles(paths)
                .onSuccess { c -> _toasts.emit("$c items deleted"); browseRemote(connId, _state.value.remotePath) }
                .onFailure { e -> _toasts.emit("Delete failed: ${e.message}") }
        }
    }

    fun createRemoteFolder(name: String) {
        val connId = _state.value.browsingConnectionId ?: return
        val repo = connectionManager.getActiveRepo(connId) ?: return
        viewModelScope.launch {
            repo.createDirectory("${_state.value.remotePath.trimEnd('/')}/$name")
                .onSuccess { _toasts.emit("Folder created"); browseRemote(connId, _state.value.remotePath) }
                .onFailure { e -> _toasts.emit("Create failed: ${e.message}") }
        }
    }

    fun testConnection(entity: ConnectionEntity) {
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = -1) }
            val result = connectionManager.testConnection(entity)
            _state.update { it.copy(isConnecting = null) }
            result.onSuccess { _toasts.emit("Connection successful") }
                .onFailure { e -> _toasts.emit("Test failed: ${e.message}") }
        }
    }

    fun saveConnection(entity: ConnectionEntity) {
        viewModelScope.launch {
            connectionManager.saveConnection(entity)
            _state.update { it.copy(showForm = false, editingConnection = null) }
            _toasts.emit("Connection saved")
        }
    }

    fun deleteConnection(entity: ConnectionEntity) {
        viewModelScope.launch {
            connectionManager.deleteConnection(entity)
            _toasts.emit("Connection deleted")
        }
    }

    fun showForm(editing: ConnectionEntity? = null) {
        _state.update { it.copy(showForm = true, editingConnection = editing) }
    }

    fun hideForm() {
        _state.update { it.copy(showForm = false, editingConnection = null) }
    }

    fun closeBrowser() {
        _state.update { it.copy(browsingConnectionId = null, remoteFiles = emptyList()) }
    }
}

// -- Screens --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    // If browsing remote files, show browser
    if (state.browsingConnectionId != null) {
        RemoteBrowserScreen(
            files = state.remoteFiles,
            currentPath = state.remotePath,
            isLoading = state.isLoadingRemote,
            connectionName = state.connections.firstOrNull { it.id == state.browsingConnectionId }?.name ?: "Remote",
            onNavigateUp = viewModel::navigateRemoteUp,
            onItemClick = viewModel::onRemoteItemClick,
            onClose = viewModel::closeBrowser,
            onCreateFolder = viewModel::createRemoteFolder,
        )
        return
    }

    // Connection form dialog
    if (state.showForm) {
        ConnectionFormDialog(
            editing = state.editingConnection,
            isTesting = state.isConnecting == -1L,
            onSave = viewModel::saveConnection,
            onTest = viewModel::testConnection,
            onDismiss = viewModel::hideForm,
        )
        return
    }

    // Connection list
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { viewModel.showForm() }) { Icon(Icons.Filled.Add, "Add Connection") } },
            )
        },
    ) { padding ->
        if (state.connections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Lan, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text("No connections", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { viewModel.showForm() }) { Text("Add Connection") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(state.connections, key = { it.id }) { conn ->
                    ConnectionListItem(
                        connection = conn,
                        isActive = conn.id in state.activeConnectionIds,
                        isConnecting = state.isConnecting == conn.id,
                        onConnect = { viewModel.connect(conn) },
                        onDisconnect = { viewModel.disconnect(conn.id) },
                        onBrowse = { viewModel.browseRemote(conn.id, conn.remotePath.ifEmpty { "/" }) },
                        onEdit = { viewModel.showForm(conn) },
                        onDelete = { viewModel.deleteConnection(conn) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionListItem(
    connection: ConnectionEntity,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val protocolIcon = when (connection.protocol) {
        "smb" -> Icons.Filled.Dns
        "sftp" -> Icons.Filled.Security
        "ftp", "ftps" -> Icons.Filled.CloudUpload
        "webdav" -> Icons.Filled.Cloud
        else -> Icons.Filled.Lan
    }
    val statusColor = if (isActive) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = AccentGreen.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
                        Text("CONNECTED", style = MaterialTheme.typography.labelSmall, color = AccentGreen,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        },
        supportingContent = {
            Text("${connection.protocol.uppercase()} - ${connection.host}:${connection.port}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = { Icon(protocolIcon, null, tint = statusColor) },
        trailingContent = {
            Row {
                if (isConnecting) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (isActive) {
                    IconButton(onClick = onBrowse) { Icon(Icons.Filled.FolderOpen, "Browse") }
                    IconButton(onClick = onDisconnect) { Icon(Icons.Filled.LinkOff, "Disconnect") }
                } else {
                    IconButton(onClick = onConnect) { Icon(Icons.Filled.Link, "Connect") }
                }
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); expanded = false },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); expanded = false },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteBrowserScreen(
    files: List<FileItem>,
    currentPath: String,
    isLoading: Boolean,
    connectionName: String,
    onNavigateUp: () -> Unit,
    onItemClick: (FileItem) -> Unit,
    onClose: () -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var showNewFolder by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(connectionName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(currentPath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onNavigateUp) { Icon(Icons.Filled.ArrowUpward, "Up") }
                    IconButton(onClick = { showNewFolder = true }) { Icon(Icons.Filled.CreateNewFolder, "New Folder") }
                },
            )
        },
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Empty directory", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New Folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onCreateFolder(name.trim()); showNewFolder = false } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectionFormDialog(
    editing: ConnectionEntity?,
    isTesting: Boolean,
    onSave: (ConnectionEntity) -> Unit,
    onTest: (ConnectionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var protocol by remember { mutableStateOf(editing?.protocol ?: "smb") }
    var host by remember { mutableStateOf(editing?.host ?: "") }
    var port by remember { mutableStateOf(editing?.port?.toString() ?: "") }
    var username by remember { mutableStateOf(editing?.username ?: "") }
    var password by remember { mutableStateOf(editing?.password ?: "") }
    var shareName by remember { mutableStateOf(editing?.shareName ?: "") }
    var remotePath by remember { mutableStateOf(editing?.remotePath ?: "/") }
    var privateKeyPath by remember { mutableStateOf(editing?.privateKeyPath ?: "") }
    var useTls by remember { mutableStateOf(editing?.useTls ?: false) }
    var showPassword by remember { mutableStateOf(false) }

    val defaultPort = Protocol.entries.firstOrNull { it.uriScheme == protocol }?.defaultPort ?: 445

    // Auto-fill port when protocol changes
    LaunchedEffect(protocol) {
        if (port.isEmpty() || port == "445" || port == "22" || port == "21" || port == "990" || port == "443") {
            port = defaultPort.toString()
        }
    }

    fun buildEntity() = ConnectionEntity(
        id = editing?.id ?: 0, name = name.ifEmpty { "$protocol://$host" },
        protocol = protocol, host = host, port = port.toIntOrNull() ?: defaultPort,
        username = username, password = password, shareName = shareName,
        remotePath = remotePath, privateKeyPath = privateKeyPath, useTls = useTls,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Edit Connection" else "New Connection") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    // Protocol selector
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("smb", "sftp", "ftp", "webdav").forEach { p ->
                            FilterChip(selected = protocol == p, onClick = { protocol = p },
                                label = { Text(p.uppercase()) })
                        }
                    }
                }
                item {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Display Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = host, onValueChange = { host = it },
                            label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                item {
                    OutlinedTextField(value = username, onValueChange = { username = it },
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Toggle")
                            }
                        })
                }
                // SMB-specific: share name
                if (protocol == "smb") {
                    item {
                        OutlinedTextField(value = shareName, onValueChange = { shareName = it },
                            label = { Text("Share Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                // SFTP-specific: key path
                if (protocol == "sftp") {
                    item {
                        OutlinedTextField(value = privateKeyPath, onValueChange = { privateKeyPath = it },
                            label = { Text("Private Key Path (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    OutlinedTextField(value = remotePath, onValueChange = { remotePath = it },
                        label = { Text("Remote Path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                // TLS toggle for FTP/WebDAV
                if (protocol in listOf("ftp", "webdav")) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Use TLS/SSL", modifier = Modifier.weight(1f))
                            Switch(checked = useTls, onCheckedChange = { useTls = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isTesting) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { onTest(buildEntity()) }, enabled = host.isNotBlank()) { Text("Test") }
                }
                TextButton(onClick = { onSave(buildEntity()) }, enabled = host.isNotBlank()) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
