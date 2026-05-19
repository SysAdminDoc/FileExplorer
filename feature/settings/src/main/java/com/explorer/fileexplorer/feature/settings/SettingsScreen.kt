package com.explorer.fileexplorer.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
        )
    }

    suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        ds.edit { it[key] = value }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {
    val state = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    fun toggleShowHidden() { viewModelScope.launch { repo.update(SettingsKeys.SHOW_HIDDEN, !state.value.showHidden) } }
    fun toggleFoldersFirst() { viewModelScope.launch { repo.update(SettingsKeys.FOLDERS_FIRST, !state.value.foldersFirst) } }
    fun toggleConfirmDelete() { viewModelScope.launch { repo.update(SettingsKeys.CONFIRM_DELETE, !state.value.confirmDelete) } }
    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { repo.update(SettingsKeys.THEME_MODE, mode.name) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                supportingContent = { Text("1.2.0") },
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
