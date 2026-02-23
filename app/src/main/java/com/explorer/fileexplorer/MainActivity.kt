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
import com.explorer.fileexplorer.core.designsystem.FileExplorerTheme
import com.explorer.fileexplorer.core.storage.PermissionHelper
import com.explorer.fileexplorer.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var permissionHelper: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FileExplorerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var hasPermission by remember { mutableStateOf(permissionHelper.hasFullStorageAccess()) }

                    if (hasPermission) {
                        AppNavigation()
                    } else {
                        PermissionScreen(
                            onGrantPermission = {
                                startActivity(permissionHelper.getManageStorageIntent())
                            },
                        )
                    }

                    // Re-check permission when returning from settings
                    LaunchedEffect(Unit) {
                        // Simple polling — will update when activity resumes
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Force recomposition to re-check permission
        setContent {
            FileExplorerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (permissionHelper.hasFullStorageAccess()) {
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
