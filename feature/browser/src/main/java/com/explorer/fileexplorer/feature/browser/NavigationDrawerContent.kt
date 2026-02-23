package com.explorer.fileexplorer.feature.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.designsystem.AccentOrange
import com.explorer.fileexplorer.core.designsystem.AccentRed
import com.explorer.fileexplorer.core.model.StorageVolume
import com.explorer.fileexplorer.core.storage.RootState

@Composable
fun NavigationDrawerContent(
    volumes: List<StorageVolume>,
    bookmarks: List<BookmarkEntity>,
    rootState: RootState,
    rootEnabled: Boolean,
    onNavigate: (String) -> Unit,
    onToggleRoot: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenApps: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text("File Explorer", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(8.dp))

            // Storage volumes
            Text("STORAGE", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            for (volume in volumes) {
                NavigationDrawerItem(
                    label = { Text(volume.name) }, selected = false,
                    onClick = { onNavigate(volume.path) },
                    icon = { Icon(if (volume.isRemovable) Icons.Filled.SdCard else Icons.Filled.Storage, null) },
                    badge = {
                        val used = "%.1f GB".format(volume.usedBytes / (1024.0 * 1024.0 * 1024.0))
                        val total = "%.1f GB".format(volume.totalBytes / (1024.0 * 1024.0 * 1024.0))
                        Text("$used / $total", style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp))
                LinearProgressIndicator(
                    progress = { volume.usagePercent },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 44.dp).height(3.dp),
                    color = if (volume.usagePercent > 0.9f) AccentRed else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Quick links
            Text("QUICK ACCESS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            val quickLinks = listOf(
                Triple("Downloads", Icons.Filled.Download, "/storage/emulated/0/Download"),
                Triple("DCIM", Icons.Filled.CameraAlt, "/storage/emulated/0/DCIM"),
                Triple("Pictures", Icons.Filled.Image, "/storage/emulated/0/Pictures"),
                Triple("Documents", Icons.Filled.Description, "/storage/emulated/0/Documents"),
                Triple("Music", Icons.Filled.MusicNote, "/storage/emulated/0/Music"),
                Triple("Movies", Icons.Filled.Movie, "/storage/emulated/0/Movies"))

            for ((name, icon, path) in quickLinks) {
                NavigationDrawerItem(label = { Text(name) }, selected = false,
                    onClick = { onNavigate(path) }, icon = { Icon(icon, null) },
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            // Bookmarks
            if (bookmarks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("BOOKMARKS", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                for (bookmark in bookmarks) {
                    NavigationDrawerItem(label = { Text(bookmark.name) }, selected = false,
                        onClick = { onNavigate(bookmark.path) },
                        icon = { Icon(Icons.Filled.Bookmark, null) },
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(8.dp))

            // Root section
            NavigationDrawerItem(
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Root (/)")
                        if (rootEnabled) {
                            Spacer(Modifier.width(8.dp))
                            Surface(color = AccentOrange.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
                                Text("ROOT", style = MaterialTheme.typography.labelSmall,
                                    color = AccentOrange,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                },
                selected = false,
                onClick = { onNavigate("/") },
                icon = { Icon(Icons.Filled.Terminal, null,
                    tint = if (rootEnabled) AccentOrange else MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Root toggle
            if (rootState == RootState.GRANTED) {
                NavigationDrawerItem(
                    label = { Text(if (rootEnabled) "Disable Root Mode" else "Enable Root Mode") },
                    selected = false,
                    onClick = onToggleRoot,
                    icon = { Icon(Icons.Filled.AdminPanelSettings, null,
                        tint = if (rootEnabled) AccentOrange else MaterialTheme.colorScheme.onSurfaceVariant) },
                    badge = {
                        Switch(checked = rootEnabled, onCheckedChange = { onToggleRoot() },
                            modifier = Modifier.height(24.dp))
                    },
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            // Root quick paths
            if (rootEnabled) {
                val rootPaths = listOf(
                    "System" to "/system",
                    "Data" to "/data",
                    "Vendor" to "/vendor",
                    "EFS" to "/efs")
                for ((name, path) in rootPaths) {
                    NavigationDrawerItem(label = { Text(name) }, selected = false,
                        onClick = { onNavigate(path) },
                        icon = { Icon(Icons.Filled.Folder, null, tint = AccentOrange) },
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
            }

            // Network
            NavigationDrawerItem(label = { Text("Network") }, selected = false,
                onClick = onOpenNetwork, icon = { Icon(Icons.Filled.Lan, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Cloud Storage
            NavigationDrawerItem(label = { Text("Cloud Storage") }, selected = false,
                onClick = onOpenCloud, icon = { Icon(Icons.Filled.Cloud, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Security
            NavigationDrawerItem(label = { Text("Security") }, selected = false,
                onClick = onOpenSecurity, icon = { Icon(Icons.Filled.Security, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // App Manager
            NavigationDrawerItem(label = { Text("App Manager") }, selected = false,
                onClick = onOpenApps, icon = { Icon(Icons.Filled.Apps, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Settings
            NavigationDrawerItem(label = { Text("Settings") }, selected = false,
                onClick = onOpenSettings, icon = { Icon(Icons.Filled.Settings, null) },
                modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
