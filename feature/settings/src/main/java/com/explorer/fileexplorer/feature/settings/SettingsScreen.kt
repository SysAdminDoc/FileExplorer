package com.explorer.fileexplorer.feature.settings

import android.widget.Toast
import android.os.Environment
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
import com.explorer.fileexplorer.core.data.BackupManager
import com.explorer.fileexplorer.core.data.BackupPreview
import com.explorer.fileexplorer.core.data.LocalTrashManager
import com.explorer.fileexplorer.core.data.PortableSettings
import com.explorer.fileexplorer.core.data.PortableSettingsPolicy
import com.explorer.fileexplorer.core.data.PortableSettingsStore
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.cloud.CloudAccountManager
import com.explorer.fileexplorer.core.model.CapabilityStatus
import com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix
import com.explorer.fileexplorer.core.model.RepositoryFeature
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import com.explorer.fileexplorer.core.designsystem.ThemeMode
import com.explorer.fileexplorer.plugin.PluginDescriptor
import com.explorer.fileexplorer.plugin.PluginManager
import com.explorer.fileexplorer.plugin.PluginTrustState
import dagger.Binds
import dagger.Module
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val SHOW_DIRECTORY_SIZES = booleanPreferencesKey("show_directory_sizes")
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

    fun write(context: android.content.Context, sizeMb: Int, location: ThumbnailCacheLocation): Boolean =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_SIZE_MB, normalizeSize(sizeMb))
            .putString(PREF_LOCATION, location.key)
            .commit()
}

data class CapabilityMatrixRow(
    val label: String,
    val matrix: RepositoryCapabilityMatrix,
)

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
    val showDirectorySizes: Boolean = false,
    val swipeLeftAction: SwipeAction = SwipeAction.NONE,
    val swipeRightAction: SwipeAction = SwipeAction.NONE,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) : PortableSettingsStore {
    private val ds = context.settingsDataStore
    private val writeMutex = Mutex()

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
            showDirectorySizes = prefs[SettingsKeys.SHOW_DIRECTORY_SIZES] ?: false,
            swipeLeftAction = SwipeAction.fromKey(prefs[SettingsKeys.SWIPE_LEFT_ACTION]),
            swipeRightAction = SwipeAction.fromKey(prefs[SettingsKeys.SWIPE_RIGHT_ACTION]),
        )
    }

    suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        writeMutex.withLock { ds.edit { it[key] = value } }
    }

    suspend fun updateThumbnailCache(sizeMb: Int, location: ThumbnailCacheLocation) {
        replacePortableSettings(
            readPortableSettings().copy(
                thumbnailCacheSizeMb = ThumbnailCacheSettings.normalizeSize(sizeMb),
                thumbnailCacheLocation = location.key,
            ),
        )
    }

    override suspend fun readPortableSettings(): PortableSettings = PortableSettingsPolicy.normalized(
        settings.first().let { current ->
            PortableSettings(
                showHidden = current.showHidden,
                foldersFirst = current.foldersFirst,
                confirmDelete = current.confirmDelete,
                defaultView = current.defaultView,
                sortField = current.sortField,
                sortDirection = current.sortDirection,
                thumbnailSize = current.thumbnailSize,
                thumbnailCacheSizeMb = current.thumbnailCacheSizeMb,
                thumbnailCacheLocation = current.thumbnailCacheLocation.key,
                themeMode = current.themeMode.name,
                trashTtlDays = current.trashTtlDays,
                compactDensity = current.compactDensity,
                showDirectorySizes = current.showDirectorySizes,
                swipeLeftAction = current.swipeLeftAction.name,
                swipeRightAction = current.swipeRightAction.name,
            )
        },
    )

    override suspend fun replacePortableSettings(settings: PortableSettings) {
        PortableSettingsPolicy.validate(settings)
        writeMutex.withLock {
            val previous = readPortableSettings()
            try {
                ds.edit { preferences ->
                    preferences[SettingsKeys.SHOW_HIDDEN] = settings.showHidden
                    preferences[SettingsKeys.FOLDERS_FIRST] = settings.foldersFirst
                    preferences[SettingsKeys.CONFIRM_DELETE] = settings.confirmDelete
                    preferences[SettingsKeys.DEFAULT_VIEW] = settings.defaultView
                    preferences[SettingsKeys.SORT_FIELD] = settings.sortField
                    preferences[SettingsKeys.SORT_DIRECTION] = settings.sortDirection
                    preferences[SettingsKeys.THUMBNAIL_SIZE] = settings.thumbnailSize
                    preferences[SettingsKeys.THUMBNAIL_CACHE_SIZE_MB] = settings.thumbnailCacheSizeMb
                    preferences[SettingsKeys.THUMBNAIL_CACHE_LOCATION] = settings.thumbnailCacheLocation
                    preferences[SettingsKeys.THEME_MODE] = settings.themeMode
                    preferences[SettingsKeys.TRASH_TTL_DAYS] = settings.trashTtlDays
                    preferences[SettingsKeys.COMPACT_DENSITY] = settings.compactDensity
                    preferences[SettingsKeys.SHOW_DIRECTORY_SIZES] = settings.showDirectorySizes
                    preferences[SettingsKeys.SWIPE_LEFT_ACTION] = settings.swipeLeftAction
                    preferences[SettingsKeys.SWIPE_RIGHT_ACTION] = settings.swipeRightAction
                }
                check(
                    ThumbnailCacheSettings.write(
                        context,
                        settings.thumbnailCacheSizeMb,
                        ThumbnailCacheLocation.fromKey(settings.thumbnailCacheLocation),
                    ),
                ) { "Unable to persist thumbnail cache settings" }
            } catch (error: Exception) {
                runCatching {
                    ds.edit { preferences ->
                        preferences[SettingsKeys.SHOW_HIDDEN] = previous.showHidden
                        preferences[SettingsKeys.FOLDERS_FIRST] = previous.foldersFirst
                        preferences[SettingsKeys.CONFIRM_DELETE] = previous.confirmDelete
                        preferences[SettingsKeys.DEFAULT_VIEW] = previous.defaultView
                        preferences[SettingsKeys.SORT_FIELD] = previous.sortField
                        preferences[SettingsKeys.SORT_DIRECTION] = previous.sortDirection
                        preferences[SettingsKeys.THUMBNAIL_SIZE] = previous.thumbnailSize
                        preferences[SettingsKeys.THUMBNAIL_CACHE_SIZE_MB] = previous.thumbnailCacheSizeMb
                        preferences[SettingsKeys.THUMBNAIL_CACHE_LOCATION] = previous.thumbnailCacheLocation
                        preferences[SettingsKeys.THEME_MODE] = previous.themeMode
                        preferences[SettingsKeys.TRASH_TTL_DAYS] = previous.trashTtlDays
                        preferences[SettingsKeys.COMPACT_DENSITY] = previous.compactDensity
                        preferences[SettingsKeys.SHOW_DIRECTORY_SIZES] = previous.showDirectorySizes
                        preferences[SettingsKeys.SWIPE_LEFT_ACTION] = previous.swipeLeftAction
                        preferences[SettingsKeys.SWIPE_RIGHT_ACTION] = previous.swipeRightAction
                    }
                }.onFailure(error::addSuppressed)
                runCatching {
                    check(
                        ThumbnailCacheSettings.write(
                            context,
                            previous.thumbnailCacheSizeMb,
                            ThumbnailCacheLocation.fromKey(previous.thumbnailCacheLocation),
                        ),
                    ) { "Unable to restore thumbnail cache settings" }
                }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBackupModule {
    @Binds
    @Singleton
    abstract fun bindPortableSettingsStore(impl: SettingsRepository): PortableSettingsStore
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val backupManager: BackupManager,
    private val diagnosticLog: com.explorer.fileexplorer.core.data.DiagnosticLog,
    private val repositoryFactory: FileRepositoryFactory,
    private val cloudAccountManager: CloudAccountManager,
    private val pluginManager: PluginManager,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val state = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())
    private val _plugins = MutableStateFlow<List<PluginDescriptor>>(emptyList())
    val plugins: StateFlow<List<PluginDescriptor>> = _plugins.asStateFlow()
    private val _capabilityMatrix = MutableStateFlow<List<CapabilityMatrixRow>>(emptyList())
    val capabilityMatrix: StateFlow<List<CapabilityMatrixRow>> = _capabilityMatrix.asStateFlow()

    private val _toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val toasts: kotlinx.coroutines.flow.SharedFlow<String> = _toasts.asSharedFlow()
    private val _importPreview = MutableStateFlow<BackupPreview?>(null)
    val importPreview: StateFlow<BackupPreview?> = _importPreview.asStateFlow()
    private var preparedBackup: com.explorer.fileexplorer.core.data.PreparedBackup? = null

    init {
        refreshPlugins()
        refreshCapabilityMatrix()
    }

    fun refreshCapabilityMatrix() {
        viewModelScope.launch(Dispatchers.IO) {
            val localPath = Environment.getExternalStorageDirectory().absolutePath
            val localRepository = repositoryFactory.getRepository(localPath)
            val rows = buildList {
                add(CapabilityMatrixRow("Local storage", localRepository.capabilityMatrix(localPath)))
                cloudAccountManager.statuses().forEach { (service, status) ->
                    add(CapabilityMatrixRow(service.displayName, status.capabilityMatrix))
                }
            }
            rows.forEach { row -> diagnosticLog.logCapabilities(row.matrix, "settings capability matrix") }
            _capabilityMatrix.value = rows
        }
    }

    fun refreshPlugins() {
        viewModelScope.launch(Dispatchers.IO) { _plugins.value = pluginManager.discover() }
    }

    fun setPluginApproval(plugin: PluginDescriptor, approved: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (approved) pluginManager.approve(plugin.id) else pluginManager.revoke(plugin.id)
            _plugins.value = pluginManager.discover()
        }
    }

    fun toggleShowHidden() { viewModelScope.launch { repo.update(SettingsKeys.SHOW_HIDDEN, !state.value.showHidden) } }
    fun toggleFoldersFirst() { viewModelScope.launch { repo.update(SettingsKeys.FOLDERS_FIRST, !state.value.foldersFirst) } }
    fun toggleConfirmDelete() { viewModelScope.launch { repo.update(SettingsKeys.CONFIRM_DELETE, !state.value.confirmDelete) } }
    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { repo.update(SettingsKeys.THEME_MODE, mode.name) } }
    fun setTrashTtlDays(days: Int) { viewModelScope.launch { repo.update(SettingsKeys.TRASH_TTL_DAYS, days) } }
    fun toggleCompactDensity() { viewModelScope.launch { repo.update(SettingsKeys.COMPACT_DENSITY, !state.value.compactDensity) } }
    fun toggleShowDirectorySizes() { viewModelScope.launch { repo.update(SettingsKeys.SHOW_DIRECTORY_SIZES, !state.value.showDirectorySizes) } }
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                out.use { backupManager.exportToStream(it) }
                _toasts.emit(context.getString(DesignSystemR.string.backup_exported))
            } catch (_: Exception) {
                _toasts.emit(context.getString(DesignSystemR.string.backup_import_failed))
            }
        }
    }

    fun previewBackup(input: java.io.InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                input.use { backupManager.prepareImport(it) }
            } catch (error: Exception) {
                Result.failure(error)
            }
            result
                .onSuccess { prepared ->
                    preparedBackup = prepared
                    _importPreview.value = prepared.preview
                }
                .onFailure {
                    _toasts.emit(context.getString(DesignSystemR.string.backup_import_failed))
                }
        }
    }

    fun cancelImport() {
        preparedBackup = null
        _importPreview.value = null
    }

    fun confirmImport() {
        val prepared = preparedBackup ?: return
        preparedBackup = null
        _importPreview.value = null
        viewModelScope.launch(Dispatchers.IO) {
            backupManager.importPrepared(prepared)
                .onSuccess { summary ->
                    _toasts.emit(
                        context.getString(
                            DesignSystemR.string.backup_import_summary,
                            summary.bookmarks,
                            summary.connections,
                            summary.settings,
                            summary.skippedBookmarks,
                            summary.skippedConnections,
                        ),
                    )
                }
                .onFailure { _toasts.emit(context.getString(DesignSystemR.string.backup_import_failed)) }
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
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val capabilityMatrix by viewModel.capabilityMatrix.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
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

            SettingsToggle(
                title = stringResource(DesignSystemR.string.show_directory_sizes),
                subtitle = stringResource(DesignSystemR.string.show_directory_sizes_description),
                checked = state.showDirectorySizes,
                onToggle = viewModel::toggleShowDirectorySizes,
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
                uri?.let { cr.openOutputStream(it)?.let(viewModel::exportBackup) }
            }

            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                uri?.let { cr.openInputStream(it)?.let(viewModel::previewBackup) }
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

            PluginSettingsPanel(
                plugins = plugins,
                onApprovalChanged = viewModel::setPluginApproval,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            CapabilityMatrixPanel(rows = capabilityMatrix)

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

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(DesignSystemR.string.backup_import_preview_title)) },
            text = {
                Text(
                    stringResource(
                        DesignSystemR.string.backup_import_preview,
                        preview.bookmarks,
                        preview.connections,
                        preview.settings,
                        preview.skippedBookmarks,
                        preview.skippedConnections,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) {
                    Text(stringResource(DesignSystemR.string.backup_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) {
                    Text(stringResource(DesignSystemR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CapabilityMatrixPanel(rows: List<CapabilityMatrixRow>) {
    Text(
        text = stringResource(DesignSystemR.string.capability_matrix).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Text(
        text = stringResource(DesignSystemR.string.capability_matrix_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    rows.forEach { row ->
        ListItem(
            headlineContent = { Text(row.label) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            DesignSystemR.string.capability_provider_location,
                            row.matrix.provider,
                            row.matrix.location,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    RepositoryFeature.entries.forEach { feature ->
                        val assessment = row.matrix.featureStatus(feature)
                        val status = assessment.status.name.lowercase().replace('_', ' ')
                        Text(
                            "${feature.displayName}: $status — ${assessment.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (assessment.status == CapabilityStatus.UNAVAILABLE) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun PluginSettingsPanel(
    plugins: List<PluginDescriptor>,
    onApprovalChanged: (PluginDescriptor, Boolean) -> Unit,
) {
    Text(
        text = stringResource(DesignSystemR.string.plugin_extensions).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Text(
        text = stringResource(DesignSystemR.string.plugin_extensions_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        text = stringResource(DesignSystemR.string.plugin_trust_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (plugins.isEmpty()) {
        Text(
            text = stringResource(DesignSystemR.string.plugin_none_installed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else {
        plugins.forEach { plugin ->
            val status = when (plugin.trustState) {
                PluginTrustState.TRUSTED -> DesignSystemR.string.plugin_trusted
                PluginTrustState.UNTRUSTED -> DesignSystemR.string.plugin_needs_approval
                PluginTrustState.SIGNATURE_CHANGED -> DesignSystemR.string.plugin_signature_changed
            }
            ListItem(
                headlineContent = { Text(plugin.displayName) },
                supportingContent = {
                    Column {
                        Text(stringResource(status))
                        Text(
                            stringResource(
                                DesignSystemR.string.plugin_capabilities,
                                plugin.capabilities.joinToString(", ") { it.wireName },
                            ),
                        )
                    }
                },
                trailingContent = {
                    Switch(
                        checked = plugin.trustState == PluginTrustState.TRUSTED,
                        onCheckedChange = { onApprovalChanged(plugin, it) },
                    )
                },
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
