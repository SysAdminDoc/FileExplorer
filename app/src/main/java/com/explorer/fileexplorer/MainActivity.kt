package com.explorer.fileexplorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.designsystem.FileExplorerTheme
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import com.explorer.fileexplorer.core.designsystem.ThemeMode
import com.explorer.fileexplorer.core.storage.PermissionHelper
import com.explorer.fileexplorer.feature.settings.SettingsRepository
import com.explorer.fileexplorer.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var permissionHelper: PermissionHelper
    @Inject lateinit var settingsRepository: SettingsRepository

    // Tracked at the activity level so onResume can refresh it after the user
    // returns from the system settings page — Compose will recompose any
    // collector when this MutableState changes.
    private val hasPermissionState = mutableStateOf(false)
    private val limitedStorageState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasPermissionState.value = permissionHelper.hasFullStorageAccess()

        setContent {
            val themeModeFlow = remember(settingsRepository) {
                settingsRepository.settings.map { it.themeMode }
            }
            val themeMode by themeModeFlow
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            FileExplorerTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (hasPermissionState.value || limitedStorageState.value) {
                        AppNavigation(
                            initialPath = if (limitedStorageState.value) {
                                permissionHelper.scopedStorageRootPath()
                            } else null,
                        )
                    } else {
                        PermissionScreen(
                            onGrantPermission = {
                                startActivity(permissionHelper.getManageStorageIntent())
                            },
                            onContinueWithLimitedAccess = {
                                limitedStorageState.value = true
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermissionState.value = permissionHelper.hasFullStorageAccess()
    }
}

@Composable
private fun PermissionScreen(
    onGrantPermission: () -> Unit,
    onContinueWithLimitedAccess: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Storage,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(DesignSystemR.string.storage_access_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(DesignSystemR.string.storage_access_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onGrantPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(DesignSystemR.string.grant_storage_access))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onContinueWithLimitedAccess,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(DesignSystemR.string.continue_limited_storage))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(DesignSystemR.string.storage_access_limited_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(DesignSystemR.string.storage_access_settings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
