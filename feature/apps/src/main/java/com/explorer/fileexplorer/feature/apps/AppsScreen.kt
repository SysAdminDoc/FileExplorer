package com.explorer.fileexplorer.feature.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class AppInfo(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val apkSize: Long,
    val apkPath: String,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val firstInstalled: Long,
    val lastUpdated: Long,
    val icon: Drawable?,
    val targetSdk: Int,
    val minSdk: Int,
)

enum class AppFilter { ALL, USER, SYSTEM, DISABLED }
enum class AppSort { NAME, SIZE, INSTALL_DATE, UPDATE_DATE, PACKAGE }

data class AppsUiState(
    val apps: List<AppInfo> = emptyList(),
    val filteredApps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filter: AppFilter = AppFilter.USER,
    val sort: AppSort = AppSort.NAME,
    val sortAsc: Boolean = true,
    val selectedApp: AppInfo? = null,
) {
    val appCount: Int get() = filteredApps.size
}

@HiltViewModel
class AppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>()
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init { loadApps() }

    private fun loadApps() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledPackages(0).mapNotNull { pkg -> packageToAppInfo(pm, pkg) }
            }
            _state.update { it.copy(apps = apps, isLoading = false) }
            applyFilterAndSort()
        }
    }

    fun setSearch(query: String) { _state.update { it.copy(searchQuery = query) }; applyFilterAndSort() }
    fun setFilter(filter: AppFilter) { _state.update { it.copy(filter = filter) }; applyFilterAndSort() }
    fun setSort(sort: AppSort) {
        _state.update { s ->
            if (s.sort == sort) s.copy(sortAsc = !s.sortAsc) else s.copy(sort = sort, sortAsc = true)
        }
        applyFilterAndSort()
    }

    fun showDetails(app: AppInfo) { _state.update { it.copy(selectedApp = app) } }
    fun hideDetails() { _state.update { it.copy(selectedApp = null) } }

    fun openApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            viewModelScope.launch { _toasts.emit("No launchable activity") }
        }
    }

    fun openAppSettings(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun shareApk(app: AppInfo) {
        try {
            val apkFile = File(app.apkPath)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            viewModelScope.launch { _toasts.emit("Share failed: ${e.message}") }
        }
    }

    fun uninstallApp(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun refresh() { loadApps() }

    private fun applyFilterAndSort() {
        val s = _state.value
        var list = s.apps

        // Filter
        list = when (s.filter) {
            AppFilter.ALL -> list
            AppFilter.USER -> list.filter { !it.isSystem }
            AppFilter.SYSTEM -> list.filter { it.isSystem }
            AppFilter.DISABLED -> list.filter { !it.isEnabled }
        }

        // Search
        if (s.searchQuery.isNotBlank()) {
            val q = s.searchQuery.lowercase()
            list = list.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }

        // Sort
        val comparator: Comparator<AppInfo> = when (s.sort) {
            AppSort.NAME -> compareBy { it.name.lowercase() }
            AppSort.SIZE -> compareBy { it.apkSize }
            AppSort.INSTALL_DATE -> compareBy { it.firstInstalled }
            AppSort.UPDATE_DATE -> compareBy { it.lastUpdated }
            AppSort.PACKAGE -> compareBy { it.packageName }
        }
        list = if (s.sortAsc) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())

        _state.update { it.copy(filteredApps = list) }
    }

    private fun packageToAppInfo(pm: PackageManager, pkg: PackageInfo): AppInfo? {
        return try {
            val appInfo = pkg.applicationInfo ?: return null
            val apkFile = File(appInfo.sourceDir)
            AppInfo(
                packageName = pkg.packageName,
                name = appInfo.loadLabel(pm).toString(),
                versionName = pkg.versionName ?: "",
                versionCode = pkg.longVersionCode,
                apkSize = apkFile.length(),
                apkPath = appInfo.sourceDir,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isEnabled = appInfo.enabled,
                firstInstalled = pkg.firstInstallTime,
                lastUpdated = pkg.lastUpdateTime,
                icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null },
                targetSdk = appInfo.targetSdkVersion,
                minSdk = appInfo.minSdkVersion,
            )
        } catch (_: Exception) { null }
    }
}

// -- Screen --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    viewModel: AppsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps (${state.appCount})") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    var sortExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { sortExpanded = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        AppSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                                onClick = { viewModel.setSort(sort); sortExpanded = false },
                                trailingIcon = {
                                    if (state.sort == sort) Icon(
                                        if (state.sortAsc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                        null, modifier = Modifier.size(16.dp))
                                })
                        }
                    }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Search
            OutlinedTextField(value = state.searchQuery, onValueChange = viewModel::setSearch,
                placeholder = { Text("Search apps...") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))

            // Filter chips
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilter.entries.forEach { filter ->
                    FilterChip(selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.filteredApps, key = { it.packageName }) { app ->
                        AppListItem(app = app,
                            onOpen = { viewModel.openApp(app.packageName) },
                            onDetails = { viewModel.showDetails(app) })
                    }
                }
            }
        }
    }

    // App details sheet
    state.selectedApp?.let { app ->
        AppDetailsSheet(app = app, onDismiss = viewModel::hideDetails,
            onOpen = { viewModel.openApp(app.packageName) },
            onSettings = { viewModel.openAppSettings(app.packageName) },
            onShareApk = { viewModel.shareApk(app) },
            onUninstall = { viewModel.uninstallApp(app.packageName) })
    }
}

@Composable
private fun AppListItem(app: AppInfo, onOpen: () -> Unit, onDetails: () -> Unit) {
    ListItem(
        headlineContent = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${app.packageName} | ${formatApkSize(app.apkSize)}",
                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            app.icon?.let { drawable ->
                Image(bitmap = drawable.toBitmap(40, 40).asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(40.dp))
            } ?: Icon(Icons.Filled.Android, null, modifier = Modifier.size(40.dp))
        },
        trailingContent = {
            Row {
                if (!app.isSystem) {
                    IconButton(onClick = onOpen) { Icon(Icons.Filled.Launch, "Open", modifier = Modifier.size(20.dp)) }
                }
                IconButton(onClick = onDetails) { Icon(Icons.Filled.Info, "Details", modifier = Modifier.size(20.dp)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailsSheet(
    app: AppInfo, onDismiss: () -> Unit,
    onOpen: () -> Unit, onSettings: () -> Unit,
    onShareApk: () -> Unit, onUninstall: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.icon?.let { drawable ->
                    Image(bitmap = drawable.toBitmap(48, 48).asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Text(app.name, style = MaterialTheme.typography.headlineSmall)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            DetailRow("Version", "${app.versionName} (${app.versionCode})")
            DetailRow("APK Size", formatApkSize(app.apkSize))
            DetailRow("APK Path", app.apkPath)
            DetailRow("Type", if (app.isSystem) "System" else "User")
            DetailRow("Status", if (app.isEnabled) "Enabled" else "Disabled")
            DetailRow("Target SDK", app.targetSdk.toString())
            DetailRow("Min SDK", app.minSdk.toString())
            DetailRow("Installed", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(app.firstInstalled)))
            DetailRow("Updated", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(app.lastUpdated)))

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Launch, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Open")
                }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Settings, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Settings")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(onClick = onShareApk, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Share APK")
                }
                if (!app.isSystem) {
                    OutlinedButton(onClick = onUninstall, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Uninstall")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatApkSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
