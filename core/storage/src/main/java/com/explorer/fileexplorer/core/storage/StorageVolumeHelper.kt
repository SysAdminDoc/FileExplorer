package com.explorer.fileexplorer.core.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.explorer.fileexplorer.core.model.StorageVolume
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageVolumeHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun getStorageVolumes(): List<StorageVolume> {
        val volumes = mutableListOf<StorageVolume>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        // Primary internal storage
        val internal = Environment.getExternalStorageDirectory()
        val internalStat = StatFs(internal.absolutePath)
        volumes.add(
            StorageVolume(
                name = "Internal Storage",
                path = internal.absolutePath,
                totalBytes = internalStat.totalBytes,
                freeBytes = internalStat.freeBytes,
                isRemovable = false,
                isPrimary = true,
            )
        )

        // External volumes (SD cards, USB)
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir == null) continue
            val path = dir.absolutePath
            // Skip internal storage (already added)
            if (path.startsWith(internal.absolutePath)) continue

            // Extract the root mount path (e.g., /storage/XXXX-XXXX)
            val segments = path.split("/")
            val mountPoint = if (segments.size >= 3) {
                "/${segments[1]}/${segments[2]}"
            } else continue

            try {
                val stat = StatFs(mountPoint)
                volumes.add(
                    StorageVolume(
                        name = "SD Card (${segments[2]})",
                        path = mountPoint,
                        totalBytes = stat.totalBytes,
                        freeBytes = stat.freeBytes,
                        isRemovable = true,
                        isPrimary = false,
                    )
                )
            } catch (_: Exception) { /* volume not accessible */ }
        }

        return volumes
    }

    fun getVolumeForPath(path: String): StorageVolume? {
        return getStorageVolumes().firstOrNull { path.startsWith(it.path) }
    }
}
