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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.storage.ShizukuAvailability
import com.explorer.fileexplorer.core.storage.ShizukuManager
import com.explorer.fileexplorer.core.storage.ShizukuPaths
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
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
        ShizukuAvailability.UNSUPPORTED -> stringResource(DesignSystemR.string.shizuku_unsupported_title)
        ShizukuAvailability.NOT_RUNNING -> stringResource(DesignSystemR.string.shizuku_not_running_title)
        ShizukuAvailability.PERMISSION_REQUIRED -> stringResource(DesignSystemR.string.shizuku_permission_required_title)
        ShizukuAvailability.READY -> stringResource(DesignSystemR.string.shizuku_ready_title, status.uid)
    }
    val description = when (status.availability) {
        ShizukuAvailability.UNSUPPORTED -> stringResource(DesignSystemR.string.shizuku_unsupported_description)
        ShizukuAvailability.NOT_RUNNING -> stringResource(DesignSystemR.string.shizuku_not_running_description)
        ShizukuAvailability.PERMISSION_REQUIRED -> stringResource(DesignSystemR.string.shizuku_permission_required_description)
        ShizukuAvailability.READY -> stringResource(DesignSystemR.string.shizuku_ready_description)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(DesignSystemR.string.shizuku_access)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back))
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
                        Text(stringResource(DesignSystemR.string.grant_shizuku_permission))
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
                    Text(stringResource(DesignSystemR.string.browse_android_data))
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
                    Text(stringResource(DesignSystemR.string.browse_android_obb))
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(DesignSystemR.string.about_shizuku), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(DesignSystemR.string.shizuku_about_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
