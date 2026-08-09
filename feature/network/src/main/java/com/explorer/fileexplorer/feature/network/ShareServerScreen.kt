package com.explorer.fileexplorer.feature.network

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShareServerUiState(
    val config: ShareServerConfig,
    val rootPath: String = config.rootPath,
    val httpPortText: String = config.httpPort.toString(),
    val ftpPortText: String = config.ftpPort.toString(),
    val username: String = config.username,
    val password: String = config.password,
    val status: ShareServerStatus = ShareServerStatus(config = config),
    val validationError: String? = null,
)

@HiltViewModel
class ShareServerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: ShareServerController,
    private val settingsStore: ShareServerSettingsStore,
) : ViewModel() {

    private val initialConfig = settingsStore.load()
    private val _state = MutableStateFlow(ShareServerUiState(config = initialConfig))
    val state: StateFlow<ShareServerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            controller.status.collect { status ->
                _state.update { it.copy(status = status, validationError = status.error) }
            }
        }
    }

    fun setRootPath(value: String) = _state.update { it.copy(rootPath = value, validationError = null) }

    fun setHttpEnabled(value: Boolean) = _state.update {
        it.copy(config = it.config.copy(httpEnabled = value), validationError = null)
    }

    fun setFtpEnabled(value: Boolean) = _state.update {
        it.copy(config = it.config.copy(ftpEnabled = value), validationError = null)
    }

    fun setLanAccess(value: Boolean) = _state.update {
        it.copy(
            config = it.config.copy(
                bindAddress = if (value) ShareServerConfig.LAN_BIND_ADDRESS
                else ShareServerConfig.LOOPBACK_BIND_ADDRESS,
                allowInsecureLan = if (value) it.config.allowInsecureLan else false,
            ),
            validationError = null,
        )
    }

    fun setAllowInsecureLan(value: Boolean) = _state.update {
        it.copy(config = it.config.copy(allowInsecureLan = value), validationError = null)
    }

    fun setHttpPort(value: String) = _state.update { it.copy(httpPortText = value, validationError = null) }

    fun setFtpPort(value: String) = _state.update { it.copy(ftpPortText = value, validationError = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value, validationError = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, validationError = null) }

    fun start() {
        val current = _state.value
        val httpPort = current.httpPortText.toIntOrNull()
        val ftpPort = current.ftpPortText.toIntOrNull()
        val error = when {
            current.rootPath.isBlank() -> "Choose a shared folder"
            !current.config.httpEnabled && !current.config.ftpEnabled -> "Enable HTTP or FTP"
            current.config.httpEnabled && httpPort !in ShareServerConfig.MIN_PORT..ShareServerConfig.MAX_PORT ->
                "HTTP port must be between " + ShareServerConfig.MIN_PORT + " and " + ShareServerConfig.MAX_PORT
            current.config.ftpEnabled && ftpPort !in ShareServerConfig.MIN_PORT..ShareServerConfig.MAX_PORT ->
                "FTP port must be between " + ShareServerConfig.MIN_PORT + " and " + ShareServerConfig.MAX_PORT
            current.config.httpEnabled && current.config.ftpEnabled && httpPort == ftpPort ->
                "HTTP and FTP ports must be different"
            current.username.isBlank() -> "Username is required"
            current.username.contains(':') -> "Username cannot contain ':'"
            current.password.isBlank() -> "Password is required"
            current.password.length < ShareServerConfig.MIN_PASSWORD_LENGTH ->
                "Password must be at least ${ShareServerConfig.MIN_PASSWORD_LENGTH} characters"
            current.config.bindAddress == ShareServerConfig.LAN_BIND_ADDRESS &&
                !current.config.allowInsecureLan ->
                "LAN sharing requires explicit insecure transport acknowledgement"
            else -> null
        }
        if (error != null || httpPort == null || ftpPort == null) {
            _state.update { it.copy(validationError = error ?: "Enter valid port numbers") }
            return
        }

        val config = current.config.copy(
            rootPath = current.rootPath,
            httpPort = httpPort,
            ftpPort = ftpPort,
            username = current.username,
            password = current.password,
        )
        settingsStore.save(config.normalized())
        _state.update { it.copy(config = config, validationError = null) }
        ShareServerService.start(context)
    }

    fun stop() {
        ShareServerService.stop(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareServerScreen(
    viewModel: ShareServerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }
    val running = state.status.isRunning

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(DesignSystemR.string.share_server)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShareServerStatusCard(state.status)

            OutlinedTextField(
                value = state.rootPath,
                onValueChange = viewModel::setRootPath,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(DesignSystemR.string.shared_folder)) },
                leadingIcon = { Icon(Icons.Filled.Folder, null) },
                singleLine = true,
                enabled = !running,
                supportingText = { Text(stringResource(DesignSystemR.string.server_cannot_leave_folder)) },
            )

            Text(stringResource(DesignSystemR.string.protocols), style = MaterialTheme.typography.titleMedium)
            ProtocolToggle(
                title = stringResource(DesignSystemR.string.http_web_access),
                subtitle = stringResource(DesignSystemR.string.http_web_access_description),
                checked = state.config.httpEnabled,
                enabled = !running,
                onCheckedChange = viewModel::setHttpEnabled,
            )
            if (state.config.httpEnabled) {
                OutlinedTextField(
                    value = state.httpPortText,
                    onValueChange = viewModel::setHttpPort,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(DesignSystemR.string.http_port)) },
                    singleLine = true,
                    enabled = !running,
                )
            }
            ProtocolToggle(
                title = stringResource(DesignSystemR.string.ftp_access),
                subtitle = stringResource(DesignSystemR.string.ftp_access_description),
                checked = state.config.ftpEnabled,
                enabled = !running,
                onCheckedChange = viewModel::setFtpEnabled,
            )
            if (state.config.ftpEnabled) {
                OutlinedTextField(
                    value = state.ftpPortText,
                    onValueChange = viewModel::setFtpPort,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(DesignSystemR.string.ftp_port)) },
                    singleLine = true,
                    enabled = !running,
                )
            }

            HorizontalDivider()
            Text(stringResource(DesignSystemR.string.share_access_scope), style = MaterialTheme.typography.titleMedium)
            ProtocolToggle(
                title = stringResource(DesignSystemR.string.share_lan_access),
                subtitle = stringResource(DesignSystemR.string.share_lan_access_description),
                checked = state.config.bindAddress == ShareServerConfig.LAN_BIND_ADDRESS,
                enabled = !running,
                onCheckedChange = viewModel::setLanAccess,
            )
            if (state.config.bindAddress == ShareServerConfig.LAN_BIND_ADDRESS) {
                ProtocolToggle(
                    title = stringResource(DesignSystemR.string.share_allow_insecure_lan),
                    subtitle = stringResource(DesignSystemR.string.share_allow_insecure_lan_description),
                    checked = state.config.allowInsecureLan,
                    enabled = !running,
                    onCheckedChange = viewModel::setAllowInsecureLan,
                )
                Text(
                    stringResource(DesignSystemR.string.share_insecure_lan_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    stringResource(DesignSystemR.string.share_loopback_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(DesignSystemR.string.share_resource_limits),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text(stringResource(DesignSystemR.string.authentication), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(DesignSystemR.string.username)) },
                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                singleLine = true,
                enabled = !running,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(DesignSystemR.string.password)) },
                singleLine = true,
                enabled = !running,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            stringResource(DesignSystemR.string.show_password),
                        )
                    }
                },
            )
            Text(
                stringResource(DesignSystemR.string.authentication_required_connections),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.validationError?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (running) {
                    Button(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(DesignSystemR.string.stop_server))
                    }
                } else {
                    Button(onClick = viewModel::start, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(DesignSystemR.string.start_server))
                    }
                }
                OutlinedButton(onClick = onNavigateBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(DesignSystemR.string.done))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareServerStatusCard(status: ShareServerStatus) {
    val running = status.isRunning
    val color = when {
        status.state == ShareServerState.FAILED -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Icon(
                    if (status.state == ShareServerState.FAILED) Icons.Filled.ErrorOutline else
                        if (running) Icons.Filled.WifiTethering else Icons.Filled.Stop,
                    null,
                    tint = color,
                )
            },
            headlineContent = {
                Text(
                    when (status.state) {
                        ShareServerState.STARTING -> stringResource(DesignSystemR.string.starting)
                        ShareServerState.RUNNING -> stringResource(DesignSystemR.string.running)
                        ShareServerState.FAILED -> stringResource(DesignSystemR.string.could_not_start)
                        ShareServerState.STOPPED -> stringResource(DesignSystemR.string.stopped)
                    },
                )
            },
            supportingContent = {
                Column {
                    if (status.addresses.isEmpty()) {
                        Text(stringResource(DesignSystemR.string.start_server_for_addresses))
                    } else {
                        SelectionContainer {
                            Column {
                                status.addresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    status.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(Icons.Filled.WifiTethering, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}
