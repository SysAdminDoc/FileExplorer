package com.explorer.fileexplorer.feature.settings

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.disk.DiskCache
import java.io.File

@OptIn(DelicateCoilApi::class)
object ThumbnailCacheController {
    private const val DIRECTORY_NAME = "fileexplorer-thumbnails"

    fun newImageLoader(context: Context): ImageLoader {
        val (sizeMb, location) = ThumbnailCacheSettings.read(context)
        return ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(OkioPathFactory.fromFile(cacheDirectory(context, location)))
                    .maxSizeBytes(sizeMb.toLong() * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    fun reset(context: Context) {
        SingletonImageLoader.setUnsafe(newImageLoader(context))
    }

    fun clear(context: Context) {
        runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
        ThumbnailCacheLocation.entries.forEach { location ->
            cacheDirectory(context, location).deleteRecursively()
        }
    }

    private fun cacheDirectory(context: Context, location: ThumbnailCacheLocation): File {
        val base = when (location) {
            ThumbnailCacheLocation.INTERNAL -> context.cacheDir
            ThumbnailCacheLocation.EXTERNAL -> context.externalCacheDir ?: context.cacheDir
        }
        return File(base, DIRECTORY_NAME)
    }
}
