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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.designsystem.FileExplorerTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasPermissionState.value = permissionHelper.hasFullStorageAccess()

        setContent {
            val themeMode by settingsRepository.settings
                .map { it.themeMode }
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            FileExplorerTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (hasPermissionState.value) {
                        AppNavigation()
                    } else {
                        PermissionScreen(
                            onGrantPermission = {
                                startActivity(permissionHelper.getManageStorageIntent())
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
private fun PermissionScreen(onGrantPermission: () -> Unit) {
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
            text = "Storage Access Required",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "File Explorer needs access to all files on your device to browse, manage, and organize your storage.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onGrantPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Grant Storage Access")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This will open Android Settings where you can allow file access.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
