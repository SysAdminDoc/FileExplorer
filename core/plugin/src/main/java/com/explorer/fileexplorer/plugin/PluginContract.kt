package com.explorer.fileexplorer.plugin

import android.os.Bundle
import android.os.Build
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem

/** Stable wire contract for independently installed FileExplorer plugins. */
object PluginContract {
    const val PROTOCOL_VERSION = 1
    const val ACTION_PLUGIN = "com.explorer.fileexplorer.action.PLUGIN"

    const val META_PROTOCOL_VERSION = "com.explorer.fileexplorer.plugin.PROTOCOL_VERSION"
    const val META_ID = "com.explorer.fileexplorer.plugin.ID"
    const val META_DISPLAY_NAME = "com.explorer.fileexplorer.plugin.DISPLAY_NAME"
    const val META_VERSION_NAME = "com.explorer.fileexplorer.plugin.VERSION_NAME"
    const val META_SCHEMES = "com.explorer.fileexplorer.plugin.SCHEMES"
    const val META_CAPABILITIES = "com.explorer.fileexplorer.plugin.CAPABILITIES"

    const val KEY_OPERATION = "operation"
    const val KEY_PATH = "path"
    const val KEY_PATHS = "paths"
    const val KEY_DESTINATION = "destination"
    const val KEY_NEW_NAME = "new_name"
    const val KEY_QUERY = "query"
    const val KEY_REGEX = "regex"
    const val KEY_INCLUDE_HIDDEN = "include_hidden"
    const val KEY_ALGORITHM = "algorithm"
    const val KEY_CONFLICT = "conflict"
    const val KEY_CONFLICT_SUFFIX = "conflict_suffix"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_COUNT = "count"
    const val KEY_EXISTS = "exists"
    const val KEY_SIZE = "size"
    const val KEY_CHECKSUM = "checksum"
    const val KEY_ITEM = "item"
    const val KEY_ENTRIES = "entries"

    const val OP_LIST = "list"
    const val OP_INFO = "info"
    const val OP_EXISTS = "exists"
    const val OP_COPY = "copy"
    const val OP_MOVE = "move"
    const val OP_DELETE = "delete"
    const val OP_CREATE_DIRECTORY = "create_directory"
    const val OP_CREATE_FILE = "create_file"
    const val OP_RENAME = "rename"
    const val OP_SIZE = "size"
    const val OP_SEARCH = "search"
    const val OP_CHECKSUM = "checksum"
}

enum class PluginCapability(val wireName: String) {
    FILESYSTEM("filesystem"),
    ARCHIVE("archive"),
    TOOL("tool");

    companion object {
        fun fromWireName(value: String): PluginCapability? =
            entries.firstOrNull { it.wireName == value }

        /** The current IPC operations are filesystem operations. Unknown operations fail closed. */
        fun requiredFor(operation: String): PluginCapability? = when (operation) {
            PluginContract.OP_LIST,
            PluginContract.OP_INFO,
            PluginContract.OP_EXISTS,
            PluginContract.OP_COPY,
            PluginContract.OP_MOVE,
            PluginContract.OP_DELETE,
            PluginContract.OP_CREATE_DIRECTORY,
            PluginContract.OP_CREATE_FILE,
            PluginContract.OP_RENAME,
            PluginContract.OP_SIZE,
            PluginContract.OP_SEARCH,
            PluginContract.OP_CHECKSUM -> FILESYSTEM
            else -> null
        }
    }
}

enum class PluginTrustState {
    /** No explicit approval has been recorded for this component and signing certificate. */
    UNTRUSTED,

    /** The component and signing certificate match an explicit user approval. */
    TRUSTED,

    /** An approval exists, but the installed component or signing certificate changed. */
    SIGNATURE_CHANGED,
}

data class PluginDescriptor(
    val id: String,
    val displayName: String,
    val versionName: String,
    val protocolVersion: Int,
    val packageName: String,
    val serviceClassName: String,
    val schemes: Set<String>,
    val capabilities: Set<PluginCapability>,
    val signatureDigest: String = "",
    val trustState: PluginTrustState = PluginTrustState.UNTRUSTED,
    val approvedCapabilities: Set<PluginCapability> = emptySet(),
)

data class PluginMetadata(
    val protocolVersion: Int,
    val id: String,
    val displayName: String,
    val versionName: String,
    val packageName: String,
    val serviceClassName: String,
    val schemes: String?,
    val capabilities: String?,
)

object PluginDescriptorCodec {
    private val identifierPattern = Regex("^[a-z][a-z0-9+.-]*$")

    fun decode(metadata: PluginMetadata): PluginDescriptor? {
        if (metadata.protocolVersion != PluginContract.PROTOCOL_VERSION) return null
        if (!metadata.id.isValidIdentifier() || metadata.displayName.isBlank()) return null
        if (metadata.packageName.isBlank() || metadata.serviceClassName.isBlank()) return null

        val schemes = parseNames(metadata.schemes)
        if (schemes.any { !it.matches(identifierPattern) }) return null

        val capabilities = parseNames(metadata.capabilities).mapNotNull(PluginCapability::fromWireName).toSet()

        return PluginDescriptor(
            id = metadata.id,
            displayName = metadata.displayName.trim(),
            versionName = metadata.versionName.ifBlank { "0" }.trim(),
            protocolVersion = metadata.protocolVersion,
            packageName = metadata.packageName,
            serviceClassName = metadata.serviceClassName,
            schemes = schemes,
            capabilities = capabilities,
        )
    }

    private fun parseNames(value: String?): Set<String> = value.orEmpty()
        .split(',', ';', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()

    private fun String.isValidIdentifier(): Boolean = length in 1..128 && matches(identifierPattern)
}

object PluginRequests {
    fun list(path: String) = request(PluginContract.OP_LIST, path)
    fun info(path: String) = request(PluginContract.OP_INFO, path)
    fun exists(path: String) = request(PluginContract.OP_EXISTS, path)

    fun copy(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String? = null,
    ) = request(PluginContract.OP_COPY).apply {
        putStringArrayList(PluginContract.KEY_PATHS, ArrayList(sources))
        putString(PluginContract.KEY_DESTINATION, destination)
        putString(PluginContract.KEY_CONFLICT, conflictResolution.name)
        conflictSuffix?.let { putString(PluginContract.KEY_CONFLICT_SUFFIX, it) }
    }

    fun move(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution,
        conflictSuffix: String? = null,
    ) = copy(sources, destination, conflictResolution, conflictSuffix).apply {
        putString(PluginContract.KEY_OPERATION, PluginContract.OP_MOVE)
    }

    fun delete(paths: List<String>) = request(PluginContract.OP_DELETE).apply {
        putStringArrayList(PluginContract.KEY_PATHS, ArrayList(paths))
    }

    fun createDirectory(path: String) = request(PluginContract.OP_CREATE_DIRECTORY, path)
    fun createFile(path: String) = request(PluginContract.OP_CREATE_FILE, path)

    fun rename(path: String, newName: String) = request(PluginContract.OP_RENAME, path).apply {
        putString(PluginContract.KEY_NEW_NAME, newName)
    }

    fun size(paths: List<String>) = request(PluginContract.OP_SIZE).apply {
        putStringArrayList(PluginContract.KEY_PATHS, ArrayList(paths))
    }

    fun search(rootPath: String, query: String, regex: Boolean, includeHidden: Boolean) =
        request(PluginContract.OP_SEARCH, rootPath).apply {
            putString(PluginContract.KEY_QUERY, query)
            putBoolean(PluginContract.KEY_REGEX, regex)
            putBoolean(PluginContract.KEY_INCLUDE_HIDDEN, includeHidden)
        }

    fun checksum(path: String, algorithm: String) = request(PluginContract.OP_CHECKSUM, path).apply {
        putString(PluginContract.KEY_ALGORITHM, algorithm)
    }

    private fun request(operation: String, path: String? = null) = Bundle().apply {
        putString(PluginContract.KEY_OPERATION, operation)
        path?.let { putString(PluginContract.KEY_PATH, it) }
    }
}

object PluginResponses {
    fun requireSuccess(response: Bundle): Bundle {
        if (!response.getBoolean(PluginContract.KEY_OK, false)) {
            throw PluginCallException(
                kind = PluginFailureKind.REMOTE_ERROR,
                message = response.getString(PluginContract.KEY_ERROR)
                    ?.take(MAX_PLUGIN_ERROR_LENGTH)
                    ?: "Plugin operation failed",
            )
        }
        return response
    }
}

object PluginProtocol {
    /** Returns the only protocol version this host can safely execute, or null to fail closed. */
    fun negotiate(remoteVersion: Int): Int? =
        remoteVersion.takeIf { it == PluginContract.PROTOCOL_VERSION }
}

enum class PluginFailureKind {
    UNTRUSTED,
    CAPABILITY_DENIED,
    PROTOCOL_MISMATCH,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    RESOURCE_LIMIT,
    TIMEOUT,
    BIND_FAILED,
    BINDER_DIED,
    REMOTE_ERROR,
}

class PluginCallException(
    val kind: PluginFailureKind,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message.take(MAX_PLUGIN_ERROR_LENGTH), cause)

private const val MAX_PLUGIN_ERROR_LENGTH = 256

object PluginFileCodec {
    fun toBundle(item: FileItem): Bundle = Bundle().apply {
        putString("name", item.name)
        putString("path", item.path)
        putLong("size", item.size)
        putLong("last_modified", item.lastModified)
        putBoolean("directory", item.isDirectory)
        putBoolean("hidden", item.isHidden)
        putBoolean("readable", item.isReadable)
        putBoolean("writable", item.isWritable)
        putString("mime_type", item.mimeType)
        putString("extension", item.extension)
    }

    fun fromBundle(bundle: Bundle): FileItem? {
        val path = bundle.getString("path")?.takeIf { it.isNotBlank() } ?: return null
        val name = bundle.getString("name")?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
        val isDirectory = bundle.getBoolean("directory", false)
        return FileItem(
            name = name,
            path = path,
            size = bundle.getLong("size", 0L),
            lastModified = bundle.getLong("last_modified", 0L),
            isDirectory = isDirectory,
            isHidden = bundle.getBoolean("hidden", false),
            isReadable = bundle.getBoolean("readable", true),
            isWritable = bundle.getBoolean("writable", true),
            mimeType = bundle.getString("mime_type")
                ?: if (isDirectory) "inode/directory" else "application/octet-stream",
            extension = bundle.getString("extension") ?: name.substringAfterLast('.', ""),
        )
    }

    fun entries(response: Bundle): List<FileItem> = parcelableEntries(response)
        .orEmpty()
        .mapNotNull(::fromBundle)

    fun item(response: Bundle): FileItem? = response.getBundle(PluginContract.KEY_ITEM)?.let(::fromBundle)

    @Suppress("DEPRECATION")
    private fun parcelableEntries(response: Bundle): ArrayList<Bundle>? = if (Build.VERSION.SDK_INT >= 33) {
        response.getParcelableArrayList(PluginContract.KEY_ENTRIES, Bundle::class.java)
    } else {
        response.getParcelableArrayList(PluginContract.KEY_ENTRIES)
    }
}
