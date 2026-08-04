package com.explorer.fileexplorer.core.data

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class UsbDeviceInfo(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val hasUsbPermission: Boolean,
)

data class UsbStorageRoot(
    val path: String,
    val name: String,
    val canWrite: Boolean,
)

/** Opaque, slash-safe paths for persisted SAF tree roots and their children. */
object UsbPathCodec {
    const val SCHEME = "usb"
    private const val PREFIX = "$SCHEME://"

    fun rootPath(treeUri: String): String = "$PREFIX${encode(treeUri)}"

    fun childPath(parentPath: String, name: String): String =
        "${parentPath.trimEnd('/')}/${encode(name)}"

    fun isUsbPath(path: String): Boolean = path.startsWith(PREFIX)

    fun treeUriString(path: String): String? {
        if (!isUsbPath(path)) return null
        val token = path.removePrefix(PREFIX).substringBefore('/')
        return decode(token)
    }

    fun treeUri(path: String): Uri? = treeUriString(path)?.let(Uri::parse)

    fun segments(path: String): List<String> {
        if (!isUsbPath(path)) return emptyList()
        return path.removePrefix(PREFIX)
            .substringAfter('/', "")
            .split('/')
            .filter { it.isNotEmpty() }
            .mapNotNull(::decode)
    }

    fun name(path: String): String? = segments(path).lastOrNull()

    fun parentPath(path: String): String? {
        if (!isUsbPath(path) || segments(path).isEmpty()) return null
        return path.substringBeforeLast('/')
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()
}

@Singleton
class UsbStorageManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun connectedDevices(): List<UsbDeviceInfo> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values
            .filter(::isMassStorageDevice)
            .map { device ->
                UsbDeviceInfo(
                    deviceId = device.deviceId,
                    name = device.productName ?: device.manufacturerName ?: "USB storage",
                    vendorId = device.vendorId,
                    productId = device.productId,
                    hasUsbPermission = runCatching { manager.hasPermission(device) }.getOrDefault(false),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun savedRoots(): List<UsbStorageRoot> = withContext(Dispatchers.IO) {
        storedTreeUris().mapNotNull { rawUri ->
            val uri = rawUri.toUri()
            val document = DocumentFile.fromTreeUri(context, uri) ?: return@mapNotNull null
            if (!document.exists() || !document.isDirectory) return@mapNotNull null
            UsbStorageRoot(
                path = UsbPathCodec.rootPath(rawUri),
                name = document.name ?: "USB storage",
                canWrite = document.canWrite(),
            )
        }
    }

    suspend fun saveTree(uri: Uri): Result<UsbStorageRoot> = withContext(Dispatchers.IO) {
        if (uri.scheme != ContentResolverScheme.CONTENT || !DocumentsContract.isTreeUri(uri)) {
            return@withContext Result.failure(IllegalArgumentException("Choose a USB storage folder"))
        }
        val document = DocumentFile.fromTreeUri(context, uri)
            ?: return@withContext Result.failure(IllegalArgumentException("The selected folder is unavailable"))
        if (!document.exists() || !document.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("The selected folder is not a directory"))
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (error: SecurityException) {
            return@withContext Result.failure(
                IllegalStateException("Android did not grant persistent USB access", error),
            )
        }
        val rawUri = uri.toString()
        preferences.edit {
            putStringSet(PREF_TREE_URIS, storedTreeUris().toMutableSet().apply { add(rawUri) })
        }
        Result.success(
            UsbStorageRoot(
                path = UsbPathCodec.rootPath(rawUri),
                name = document.name ?: "USB storage",
                canWrite = document.canWrite(),
            ),
        )
    }

    suspend fun forgetTree(path: String): Boolean = withContext(Dispatchers.IO) {
        val rawUri = UsbPathCodec.treeUriString(path) ?: return@withContext false
        val changed = storedTreeUris().toMutableSet().remove(rawUri)
        if (!changed) return@withContext false
        preferences.edit {
            putStringSet(PREF_TREE_URIS, storedTreeUris().filterNot { it == rawUri }.toSet())
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                rawUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        true
    }

    fun documentForPath(path: String): DocumentFile? {
        val rootUri = UsbPathCodec.treeUri(path) ?: return null
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (segment in UsbPathCodec.segments(path)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun storedTreeUris(): Set<String> =
        preferences.getStringSet(PREF_TREE_URIS, emptySet()).orEmpty().toSet()

    private fun isMassStorageDevice(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
            (0 until device.interfaceCount).any { index ->
                device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }

    private companion object {
        const val PREFERENCES_NAME = "usb_storage"
        const val PREF_TREE_URIS = "tree_uris"
    }
}

private object ContentResolverScheme {
    const val CONTENT = "content"
}
