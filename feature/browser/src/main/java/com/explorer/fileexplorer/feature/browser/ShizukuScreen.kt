package com.explorer.fileexplorer.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.storage.ShizukuAvailability
import com.explorer.fileexplorer.core.storage.ShizukuManager
import com.explorer.fileexplorer.core.storage.ShizukuPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager,
) : ViewModel() {
    val status = shizukuManager.status

    fun refresh() = shizukuManager.refresh()
    fun requestPermission() = shizukuManager.requestPermission()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(
    viewModel: ShizukuViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onOpenAndroidData: (String) -> Unit = {},
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val title = when (status.availability) {
        ShizukuAvailability.UNSUPPORTED -> "Unsupported Shizuku version"
        ShizukuAvailability.NOT_RUNNING -> "Shizuku is not running"
        ShizukuAvailability.PERMISSION_REQUIRED -> "Permission required"
        ShizukuAvailability.READY -> "Shizuku access ready (UID ${status.uid})"
    }
    val description = when (status.availability) {
        ShizukuAvailability.UNSUPPORTED -> "This device is running a Shizuku server older than API v11."
        ShizukuAvailability.NOT_RUNNING -> "Install and start Shizuku or Sui, then return here. Shizuku is optional."
        ShizukuAvailability.PERMISSION_REQUIRED -> "Shizuku is running. Grant File Explorer permission to browse Android/data."
        ShizukuAvailability.READY -> "File Explorer can browse and manage Android/data through the granted Shizuku UserService."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shizuku Access") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(description) },
                    leadingContent = {
                        Icon(
                            if (status.isReady) Icons.Filled.AdminPanelSettings else Icons.Filled.Info,
                            null,
                            tint = if (status.isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            if (status.availability == ShizukuAvailability.PERMISSION_REQUIRED) {
                item {
                    Button(
                        onClick = viewModel::requestPermission,
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    ) {
                        Text("Grant Shizuku permission")
                    }
                }
            }
            item {
                Button(
                    onClick = { onOpenAndroidData(ShizukuPaths.ANDROID_DATA_ROOT) },
                    enabled = status.isReady,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Browse Android/data")
                }
            }
            item {
                Button(
                    onClick = { onOpenAndroidData(ShizukuPaths.ANDROID_OBB_ROOT) },
                    enabled = status.isReady,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Browse Android/obb")
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("About Shizuku", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Shizuku is a separate, user-started service. On non-rooted Android 11+ devices it can be started with Wireless debugging. " +
                            "File Explorer only uses it for paths under /storage/emulated/0/Android/data and does not request or store service credentials.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
