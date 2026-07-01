package com.explorer.fileexplorer.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.LocalTrashManager
import com.explorer.fileexplorer.core.designsystem.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// DataStore instance
val android.content.Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

object SettingsKeys {
    val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
    val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
    val CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
    val DEFAULT_VIEW = stringPreferencesKey("default_view")
    val SORT_FIELD = stringPreferencesKey("sort_field")
    val SORT_DIRECTION = stringPreferencesKey("sort_direction")
    val THUMBNAIL_SIZE = intPreferencesKey("thumbnail_size")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val TRASH_TTL_DAYS = intPreferencesKey("trash_ttl_days")
    val COMPACT_DENSITY = booleanPreferencesKey("compact_density")
}

data class SettingsState(
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val confirmDelete: Boolean = false,
    val defaultView: String = "LIST",
    val sortField: String = "NAME",
    val sortDirection: String = "ASCENDING",
    val thumbnailSize: Int = 48,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val trashTtlDays: Int = LocalTrashManager.DEFAULT_TTL_DAYS,
    val compactDensity: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) {
    private val ds = context.settingsDataStore

    val settings: Flow<SettingsState> = ds.data.map { prefs ->
        SettingsState(
            showHidden = prefs[SettingsKeys.SHOW_HIDDEN] ?: false,
            foldersFirst = prefs[SettingsKeys.FOLDERS_FIRST] ?: true,
            confirmDelete = prefs[SettingsKeys.CONFIRM_DELETE] ?: false,
            defaultView = prefs[SettingsKeys.DEFAULT_VIEW] ?: "LIST",
            sortField = prefs[SettingsKeys.SORT_FIELD] ?: "NAME",
            sortDirection = prefs[SettingsKeys.SORT_DIRECTION] ?: "ASCENDING",
            thumbnailSize = prefs[SettingsKeys.THUMBNAIL_SIZE] ?: 48,
            themeMode = ThemeMode.fromKey(prefs[SettingsKeys.THEME_MODE]),
            trashTtlDays = prefs[SettingsKeys.TRASH_TTL_DAYS] ?: LocalTrashManager.DEFAULT_TTL_DAYS,
            compactDensity = prefs[SettingsKeys.COMPACT_DENSITY] ?: false,
        )
    }

    suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        ds.edit { it[key] = value }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val backupManager: com.explorer.fileexplorer.core.data.BackupManager,
    private val diagnosticLog: com.explorer.fileexplorer.core.data.DiagnosticLog,
) : ViewModel() {
    val state = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    private val _toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val toasts: kotlinx.coroutines.flow.SharedFlow<String> = _toasts.asSharedFlow()

    fun toggleShowHidden() { viewModelScope.launch { repo.update(SettingsKeys.SHOW_HIDDEN, !state.value.showHidden) } }
    fun toggleFoldersFirst() { viewModelScope.launch { repo.update(SettingsKeys.FOLDERS_FIRST, !state.value.foldersFirst) } }
    fun toggleConfirmDelete() { viewModelScope.launch { repo.update(SettingsKeys.CONFIRM_DELETE, !state.value.confirmDelete) } }
    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { repo.update(SettingsKeys.THEME_MODE, mode.name) } }
    fun setTrashTtlDays(days: Int) { viewModelScope.launch { repo.update(SettingsKeys.TRASH_TTL_DAYS, days) } }
    fun toggleCompactDensity() { viewModelScope.launch { repo.update(SettingsKeys.COMPACT_DENSITY, !state.value.compactDensity) } }

    fun exportBackup(out: java.io.OutputStream) {
        viewModelScope.launch {
            backupManager.exportToStream(out)
            _toasts.emit("Backup exported")
        }
    }

    fun importBackup(input: java.io.InputStream) {
        viewModelScope.launch {
            backupManager.importFromStream(input)
                .onSuccess { s -> _toasts.emit("Imported ${s.bookmarks} bookmarks, ${s.connections} connections") }
                .onFailure { e -> _toasts.emit("Import failed: ${e.message}") }
        }
    }

    fun shareDiagnosticLog(context: android.content.Context) {
        viewModelScope.launch {
            val text = diagnosticLog.exportToString()
            if (text.lines().size <= 4) {
                _toasts.emit("No diagnostic entries to export")
                return@launch
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "FileExplorer Diagnostic Log")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share diagnostic log"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appContext = LocalContext.current

    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(appContext, it, Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Theme section
            Text(
                text = "THEME",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            ThemeSelector(
                current = state.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Display section
            Text(
                text = "DISPLAY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SettingsToggle(
                title = "Show hidden files",
                subtitle = "Display files starting with a dot",
                checked = state.showHidden,
                onToggle = viewModel::toggleShowHidden,
            )

            SettingsToggle(
                title = "Folders first",
                subtitle = "Always show folders before files",
                checked = state.foldersFirst,
                onToggle = viewModel::toggleFoldersFirst,
            )

            SettingsToggle(
                title = "Compact density",
                subtitle = "Reduce spacing for more visible files per screen",
                checked = state.compactDensity,
                onToggle = viewModel::toggleCompactDensity,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Behavior section
            Text(
                text = "BEHAVIOR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SettingsToggle(
                title = "Confirm delete",
                subtitle = "Show confirmation before deleting files",
                checked = state.confirmDelete,
                onToggle = viewModel::toggleConfirmDelete,
            )

            TrashTtlSelector(
                currentDays = state.trashTtlDays,
                onSelect = viewModel::setTrashTtlDays,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Backup
            Text(
                text = "BACKUP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            val cr = appContext.contentResolver
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                uri?.let { cr.openOutputStream(it)?.use { out -> viewModel.exportBackup(out) } }
            }

            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                uri?.let { cr.openInputStream(it)?.use { input -> viewModel.importBackup(input) } }
            }

            ListItem(
                headlineContent = { Text("Export bookmarks") },
                supportingContent = { Text("Save bookmarks and settings to a JSON file") },
                leadingContent = { Icon(Icons.Filled.FileUpload, null) },
                modifier = Modifier.clickable { exportLauncher.launch("fileexplorer-backup.json") },
            )

            ListItem(
                headlineContent = { Text("Import bookmarks") },
                supportingContent = { Text("Restore from a previously exported backup") },
                leadingContent = { Icon(Icons.Filled.FileDownload, null) },
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json")) },
            )

            ListItem(
                headlineContent = { Text("Export diagnostic log") },
                supportingContent = { Text("Share error logs for troubleshooting") },
                leadingContent = { Icon(Icons.Filled.FileUpload, null) },
                modifier = Modifier.clickable { viewModel.shareDiagnosticLog(appContext) },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About
            Text(
                text = "ABOUT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text("1.4.0") },
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = { onToggle() })
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashTtlSelector(
    currentDays: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(7, 14, 30, 60, 90)
    ListItem(
        headlineContent = { Text("Trash auto-purge") },
        supportingContent = { Text("Delete trash after $currentDays days") },
    )
    FlowRow(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { days ->
            FilterChip(
                selected = currentDays == days,
                onClick = { onSelect(days) },
                label = { Text("${days}d") },
            )
        }
    }
}

@Composable
private fun ThemeSelector(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to ("System default" to "Follow device light/dark setting"),
        ThemeMode.LIGHT to ("Light" to "Standard Material 3 light surfaces"),
        ThemeMode.DARK to ("Dark" to "Deep dark with cyan accent (current default)"),
        ThemeMode.OLED to ("OLED / True Black" to "Pure-black background, AMOLED power savings"),
        ThemeMode.DYNAMIC to ("Material You" to "Wallpaper-derived colors (Android 12+)"),
    )
    Column {
        options.forEach { (mode, labels) ->
            val (title, subtitle) = labels
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(subtitle) },
                trailingContent = {
                    RadioButton(
                        selected = current == mode,
                        onClick = { onSelect(mode) },
                    )
                },
            )
        }
    }
}
