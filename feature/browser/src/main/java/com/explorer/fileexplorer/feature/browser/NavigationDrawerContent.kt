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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.SavedSearchEntity
import com.explorer.fileexplorer.core.designsystem.AccentOrange
import com.explorer.fileexplorer.core.designsystem.AccentRed
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import com.explorer.fileexplorer.core.model.StorageVolume
import com.explorer.fileexplorer.core.data.UsbDeviceInfo
import com.explorer.fileexplorer.core.data.UsbStorageRoot
import com.explorer.fileexplorer.core.storage.RootState
import com.explorer.fileexplorer.core.storage.ShizukuPaths

@Composable
fun NavigationDrawerContent(
    volumes: List<StorageVolume>,
    usbDevices: List<UsbDeviceInfo>,
    usbRoots: List<UsbStorageRoot>,
    bookmarks: List<BookmarkEntity>,
    savedSearches: List<SavedSearchEntity>,
    rootState: RootState,
    rootEnabled: Boolean,
    onNavigate: (String) -> Unit,
    onOpenUsbPicker: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenSavedSearch: (SavedSearchEntity) -> Unit,
    onOpenTags: () -> Unit,
    onToggleRoot: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenRootModules: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenAnalyzer: () -> Unit,
    onOpenTransfers: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(stringResource(DesignSystemR.string.app_name), style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(8.dp))

            // Storage volumes
            Text(stringResource(DesignSystemR.string.storage).uppercase(), style = MaterialTheme.typography.labelSmall,
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

            if (usbDevices.isNotEmpty() || usbRoots.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(DesignSystemR.string.usb_otg), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                for (root in usbRoots) {
                    NavigationDrawerItem(
                        label = { Text(root.name) }, selected = false,
                        onClick = { onNavigate(root.path) },
                        icon = { Icon(Icons.Filled.Usb, null) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                for (device in usbDevices) {
                    NavigationDrawerItem(
                        label = { Text(device.name) }, selected = false,
                        onClick = onOpenUsbPicker,
                        icon = { Icon(Icons.Filled.Usb, null) },
                        badge = { Text(stringResource(DesignSystemR.string.choose_folder), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                OutlinedButton(
                    onClick = onOpenUsbPicker,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                ) { Text(stringResource(DesignSystemR.string.choose_usb_folder)) }
            }

            Spacer(Modifier.height(8.dp))

            // Quick links
            Text(stringResource(DesignSystemR.string.quick_access).uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            val quickLinks = listOf(
                Triple(stringResource(DesignSystemR.string.downloads), Icons.Filled.Download, "/storage/emulated/0/Download"),
                Triple("DCIM", Icons.Filled.CameraAlt, "/storage/emulated/0/DCIM"),
                Triple(stringResource(DesignSystemR.string.pictures), Icons.Filled.Image, "/storage/emulated/0/Pictures"),
                Triple(stringResource(DesignSystemR.string.documents), Icons.Filled.Description, "/storage/emulated/0/Documents"),
                Triple(stringResource(DesignSystemR.string.music), Icons.Filled.MusicNote, "/storage/emulated/0/Music"),
                Triple(stringResource(DesignSystemR.string.movies), Icons.Filled.Movie, "/storage/emulated/0/Movies"))

            for ((name, icon, path) in quickLinks) {
                NavigationDrawerItem(label = { Text(name) }, selected = false,
                    onClick = { onNavigate(path) }, icon = { Icon(icon, null) },
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.collections)) }, selected = false,
                onClick = onOpenCollections, icon = { Icon(Icons.Filled.CollectionsBookmark, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.tags)) }, selected = false,
                onClick = onOpenTags, icon = { Icon(Icons.Filled.Label, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Bookmarks
            if (bookmarks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(DesignSystemR.string.bookmarks).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                for (bookmark in bookmarks) {
                    NavigationDrawerItem(label = { Text(bookmark.name) }, selected = false,
                        onClick = { onNavigate(bookmark.path) },
                        icon = { Icon(Icons.Filled.Bookmark, null) },
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }

            if (savedSearches.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(DesignSystemR.string.saved_searches).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                for (savedSearch in savedSearches) {
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(savedSearch.name)
                                Text(
                                    savedSearch.query,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        },
                        selected = false,
                        onClick = { onOpenSavedSearch(savedSearch) },
                        icon = { Icon(Icons.Filled.Bookmark, null) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(8.dp))

            // Root section
            NavigationDrawerItem(
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(DesignSystemR.string.root))
                        if (rootEnabled) {
                            Spacer(Modifier.width(8.dp))
                            Surface(color = AccentOrange.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
                                Text(stringResource(DesignSystemR.string.root_badge), style = MaterialTheme.typography.labelSmall,
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
                    label = { Text(stringResource(if (rootEnabled) DesignSystemR.string.disable_root_mode else DesignSystemR.string.enable_root_mode)) },
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
                    stringResource(DesignSystemR.string.system) to "/system",
                    stringResource(DesignSystemR.string.data) to "/data",
                    stringResource(DesignSystemR.string.vendor) to "/vendor",
                    stringResource(DesignSystemR.string.efs) to "/efs")
                for ((name, path) in rootPaths) {
                    NavigationDrawerItem(label = { Text(name) }, selected = false,
                        onClick = { onNavigate(path) },
                        icon = { Icon(Icons.Filled.Folder, null, tint = AccentOrange) },
                        modifier = Modifier.padding(horizontal = 24.dp))
                }

                NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.root_modules)) }, selected = false,
                    onClick = onOpenRootModules, icon = { Icon(Icons.Filled.Extension, null, tint = AccentOrange) },
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            // Network
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.network)) }, selected = false,
                onClick = onOpenNetwork, icon = { Icon(Icons.Filled.Lan, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.shizuku_access)) }, selected = false,
                onClick = onOpenShizuku, icon = { Icon(Icons.Filled.AdminPanelSettings, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.android_data)) }, selected = false,
                onClick = { onNavigate(ShizukuPaths.ANDROID_DATA_ROOT) },
                icon = { Icon(Icons.Filled.Folder, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Share server
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.share_server)) }, selected = false,
                onClick = onOpenServer, icon = { Icon(Icons.Filled.WifiTethering, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Cloud Storage
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.cloud_storage)) }, selected = false,
                onClick = onOpenCloud, icon = { Icon(Icons.Filled.Cloud, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Security
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.security)) }, selected = false,
                onClick = onOpenSecurity, icon = { Icon(Icons.Filled.Security, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Trash
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.trash)) }, selected = false,
                onClick = onOpenTrash, icon = { Icon(Icons.Filled.DeleteSweep, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Storage analyzer
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.storage_analyzer)) }, selected = false,
                onClick = onOpenAnalyzer, icon = { Icon(Icons.Filled.Analytics, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Transfer queue
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.transfer_queue)) }, selected = false,
                onClick = onOpenTransfers, icon = { Icon(Icons.Filled.SwapHoriz, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // App Manager
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.app_manager)) }, selected = false,
                onClick = onOpenApps, icon = { Icon(Icons.Filled.Apps, null) },
                modifier = Modifier.padding(horizontal = 12.dp))

            // Settings
            NavigationDrawerItem(label = { Text(stringResource(DesignSystemR.string.settings)) }, selected = false,
                onClick = onOpenSettings, icon = { Icon(Icons.Filled.Settings, null) },
                modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
