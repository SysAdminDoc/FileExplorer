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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.LocalTrashManager
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
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
    val THUMBNAIL_CACHE_SIZE_MB = intPreferencesKey("thumbnail_cache_size_mb")
    val THUMBNAIL_CACHE_LOCATION = stringPreferencesKey("thumbnail_cache_location")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val TRASH_TTL_DAYS = intPreferencesKey("trash_ttl_days")
    val COMPACT_DENSITY = booleanPreferencesKey("compact_density")
    val SWIPE_LEFT_ACTION = stringPreferencesKey("swipe_left_action")
    val SWIPE_RIGHT_ACTION = stringPreferencesKey("swipe_right_action")
}

enum class ThumbnailCacheLocation(@androidx.annotation.StringRes val labelRes: Int, val key: String) {
    INTERNAL(DesignSystemR.string.thumbnail_cache_internal, "internal"),
    EXTERNAL(DesignSystemR.string.thumbnail_cache_external, "external"),
    ;

    companion object {
        fun fromKey(key: String?): ThumbnailCacheLocation = entries.firstOrNull { it.key == key } ?: INTERNAL
    }
}

object ThumbnailCacheSettings {
    const val DEFAULT_SIZE_MB = 256
    const val MIN_SIZE_MB = 32
    const val MAX_SIZE_MB = 1024
    private const val PREFS_NAME = "thumbnail_cache"
    private const val PREF_SIZE_MB = "size_mb"
    private const val PREF_LOCATION = "location"

    fun normalizeSize(sizeMb: Int): Int = sizeMb.coerceIn(MIN_SIZE_MB, MAX_SIZE_MB)

    fun read(context: android.content.Context): Pair<Int, ThumbnailCacheLocation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val size = normalizeSize(prefs.getInt(PREF_SIZE_MB, DEFAULT_SIZE_MB))
        return size to ThumbnailCacheLocation.fromKey(prefs.getString(PREF_LOCATION, ThumbnailCacheLocation.INTERNAL.key))
    }

    fun write(context: android.content.Context, sizeMb: Int, location: ThumbnailCacheLocation) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_SIZE_MB, normalizeSize(sizeMb))
            .putString(PREF_LOCATION, location.key)
            .apply()
    }
}

enum class SwipeAction(@androidx.annotation.StringRes val labelRes: Int, @androidx.annotation.StringRes val descriptionRes: Int) {
    NONE(DesignSystemR.string.no_action, DesignSystemR.string.leave_row_in_place),
    DELETE(DesignSystemR.string.delete, DesignSystemR.string.move_item_to_trash_description),
    SHARE(DesignSystemR.string.share, DesignSystemR.string.open_system_share_sheet),
    COMPRESS(DesignSystemR.string.compress, DesignSystemR.string.open_archive_dialog),
    MOVE(DesignSystemR.string.move, DesignSystemR.string.cut_then_paste),
    ;

    companion object {
        fun fromKey(key: String?): SwipeAction = entries.firstOrNull { it.name == key } ?: NONE
    }
}

data class SettingsState(
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val confirmDelete: Boolean = false,
    val defaultView: String = "LIST",
    val sortField: String = "NAME",
    val sortDirection: String = "ASCENDING",
    val thumbnailSize: Int = 48,
    val thumbnailCacheSizeMb: Int = ThumbnailCacheSettings.DEFAULT_SIZE_MB,
    val thumbnailCacheLocation: ThumbnailCacheLocation = ThumbnailCacheLocation.INTERNAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val trashTtlDays: Int = LocalTrashManager.DEFAULT_TTL_DAYS,
    val compactDensity: Boolean = false,
    val swipeLeftAction: SwipeAction = SwipeAction.NONE,
    val swipeRightAction: SwipeAction = SwipeAction.NONE,
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
            thumbnailCacheSizeMb = ThumbnailCacheSettings.normalizeSize(
                prefs[SettingsKeys.THUMBNAIL_CACHE_SIZE_MB] ?: ThumbnailCacheSettings.DEFAULT_SIZE_MB,
            ),
            thumbnailCacheLocation = ThumbnailCacheLocation.fromKey(prefs[SettingsKeys.THUMBNAIL_CACHE_LOCATION]),
            themeMode = ThemeMode.fromKey(prefs[SettingsKeys.THEME_MODE]),
            trashTtlDays = prefs[SettingsKeys.TRASH_TTL_DAYS] ?: LocalTrashManager.DEFAULT_TTL_DAYS,
            compactDensity = prefs[SettingsKeys.COMPACT_DENSITY] ?: false,
            swipeLeftAction = SwipeAction.fromKey(prefs[SettingsKeys.SWIPE_LEFT_ACTION]),
            swipeRightAction = SwipeAction.fromKey(prefs[SettingsKeys.SWIPE_RIGHT_ACTION]),
        )
    }

    suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        ds.edit { it[key] = value }
    }

    suspend fun updateThumbnailCache(sizeMb: Int, location: ThumbnailCacheLocation) {
        val normalizedSize = ThumbnailCacheSettings.normalizeSize(sizeMb)
        ds.edit {
            it[SettingsKeys.THUMBNAIL_CACHE_SIZE_MB] = normalizedSize
            it[SettingsKeys.THUMBNAIL_CACHE_LOCATION] = location.key
        }
        ThumbnailCacheSettings.write(context, normalizedSize, location)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val backupManager: com.explorer.fileexplorer.core.data.BackupManager,
    private val diagnosticLog: com.explorer.fileexplorer.core.data.DiagnosticLog,
    @ApplicationContext private val context: android.content.Context,
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
    fun setSwipeLeftAction(action: SwipeAction) { viewModelScope.launch { repo.update(SettingsKeys.SWIPE_LEFT_ACTION, action.name) } }
    fun setSwipeRightAction(action: SwipeAction) { viewModelScope.launch { repo.update(SettingsKeys.SWIPE_RIGHT_ACTION, action.name) } }
    fun setThumbnailCacheSize(sizeMb: Int) {
        viewModelScope.launch {
            repo.updateThumbnailCache(sizeMb, state.value.thumbnailCacheLocation)
            ThumbnailCacheController.reset(context)
        }
    }
    fun setThumbnailCacheLocation(location: ThumbnailCacheLocation) {
        viewModelScope.launch {
            repo.updateThumbnailCache(state.value.thumbnailCacheSizeMb, location)
            ThumbnailCacheController.reset(context)
        }
    }
    fun purgeThumbnailCache() {
        viewModelScope.launch {
            ThumbnailCacheController.clear(context)
            _toasts.emit(context.getString(DesignSystemR.string.thumbnail_cache_cleared))
        }
    }

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
                title = { Text(stringResource(DesignSystemR.string.settings)) },
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
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Theme section
            Text(
                text = stringResource(DesignSystemR.string.theme).uppercase(),
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
                text = stringResource(DesignSystemR.string.display).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SettingsToggle(
                title = stringResource(DesignSystemR.string.show_hidden_files),
                subtitle = stringResource(DesignSystemR.string.display_dotfiles),
                checked = state.showHidden,
                onToggle = viewModel::toggleShowHidden,
            )

            SettingsToggle(
                title = stringResource(DesignSystemR.string.folders_first),
                subtitle = stringResource(DesignSystemR.string.always_show_folders),
                checked = state.foldersFirst,
                onToggle = viewModel::toggleFoldersFirst,
            )

            SettingsToggle(
                title = stringResource(DesignSystemR.string.compact_density),
                subtitle = stringResource(DesignSystemR.string.reduce_spacing),
                checked = state.compactDensity,
                onToggle = viewModel::toggleCompactDensity,
            )

            ThumbnailCacheSettingsPanel(
                state = state,
                onSizeChange = viewModel::setThumbnailCacheSize,
                onLocationChange = viewModel::setThumbnailCacheLocation,
                onPurge = viewModel::purgeThumbnailCache,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Behavior section
            Text(
                text = stringResource(DesignSystemR.string.behavior).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SettingsToggle(
                title = stringResource(DesignSystemR.string.confirm_delete),
                subtitle = stringResource(DesignSystemR.string.confirm_before_delete),
                checked = state.confirmDelete,
                onToggle = viewModel::toggleConfirmDelete,
            )

            TrashTtlSelector(
                currentDays = state.trashTtlDays,
                onSelect = viewModel::setTrashTtlDays,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Text(
                text = stringResource(DesignSystemR.string.gestures).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SwipeActionSelector(
                title = stringResource(DesignSystemR.string.swipe_left),
                current = state.swipeLeftAction,
                onSelect = viewModel::setSwipeLeftAction,
            )
            SwipeActionSelector(
                title = stringResource(DesignSystemR.string.swipe_right),
                current = state.swipeRightAction,
                onSelect = viewModel::setSwipeRightAction,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Backup
            Text(
                text = stringResource(DesignSystemR.string.backup).uppercase(),
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
                headlineContent = { Text(stringResource(DesignSystemR.string.export_bookmarks)) },
                supportingContent = { Text(stringResource(DesignSystemR.string.save_bookmarks_settings)) },
                leadingContent = { Icon(Icons.Filled.FileUpload, null) },
                modifier = Modifier.clickable { exportLauncher.launch("fileexplorer-backup.json") },
            )

            ListItem(
                headlineContent = { Text(stringResource(DesignSystemR.string.import_bookmarks)) },
                supportingContent = { Text(stringResource(DesignSystemR.string.restore_backup)) },
                leadingContent = { Icon(Icons.Filled.FileDownload, null) },
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json")) },
            )

            ListItem(
                headlineContent = { Text(stringResource(DesignSystemR.string.export_diagnostic_log)) },
                supportingContent = { Text(stringResource(DesignSystemR.string.share_error_logs)) },
                leadingContent = { Icon(Icons.Filled.FileUpload, null) },
                modifier = Modifier.clickable { viewModel.shareDiagnosticLog(appContext) },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About
            Text(
                text = stringResource(DesignSystemR.string.about).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            ListItem(
                headlineContent = { Text(stringResource(DesignSystemR.string.version)) },
                supportingContent = { Text(stringResource(DesignSystemR.string.version_value)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThumbnailCacheSettingsPanel(
    state: SettingsState,
    onSizeChange: (Int) -> Unit,
    onLocationChange: (ThumbnailCacheLocation) -> Unit,
    onPurge: () -> Unit,
) {
    var sliderValue by remember(state.thumbnailCacheSizeMb) {
        mutableFloatStateOf(state.thumbnailCacheSizeMb.toFloat())
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(DesignSystemR.string.thumbnail_cache), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(DesignSystemR.string.thumbnail_cache_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(DesignSystemR.string.thumbnail_cache_size, sliderValue.toInt()))
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = ThumbnailCacheSettings.MIN_SIZE_MB.toFloat()..ThumbnailCacheSettings.MAX_SIZE_MB.toFloat(),
            steps = 15,
            onValueChangeFinished = { onSizeChange(sliderValue.toInt()) },
        )
        Text(
            stringResource(DesignSystemR.string.thumbnail_cache_size_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(DesignSystemR.string.thumbnail_cache_location),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThumbnailCacheLocation.entries.forEach { location ->
                FilterChip(
                    selected = state.thumbnailCacheLocation == location,
                    onClick = { onLocationChange(location) },
                    label = { Text(stringResource(location.labelRes)) },
                )
            }
        }
        OutlinedButton(onClick = onPurge, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(DesignSystemR.string.purge_thumbnail_cache))
        }
        Text(
            stringResource(DesignSystemR.string.purge_thumbnail_cache_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
private fun SwipeActionSelector(
    title: String,
    current: SwipeAction,
    onSelect: (SwipeAction) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(stringResource(current.descriptionRes)) },
    )
    FlowRow(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwipeAction.entries.forEach { action ->
            FilterChip(
                selected = current == action,
                onClick = { onSelect(action) },
                label = { Text(stringResource(action.labelRes)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashTtlSelector(
    currentDays: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(7, 14, 30, 60, 90)
    ListItem(
        headlineContent = { Text(stringResource(DesignSystemR.string.trash_auto_purge)) },
        supportingContent = { Text(stringResource(DesignSystemR.string.delete_trash_after_days, currentDays)) },
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
                label = { Text(stringResource(DesignSystemR.string.days_short, days)) },
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
        ThemeMode.SYSTEM to (stringResource(DesignSystemR.string.system_default) to stringResource(DesignSystemR.string.follow_device_theme)),
        ThemeMode.LIGHT to (stringResource(DesignSystemR.string.light) to stringResource(DesignSystemR.string.standard_light_surfaces)),
        ThemeMode.DARK to (stringResource(DesignSystemR.string.dark) to stringResource(DesignSystemR.string.deep_dark_surfaces)),
        ThemeMode.OLED to (stringResource(DesignSystemR.string.oled_true_black) to stringResource(DesignSystemR.string.pure_black_background)),
        ThemeMode.DYNAMIC to (stringResource(DesignSystemR.string.material_you) to stringResource(DesignSystemR.string.wallpaper_colors)),
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
