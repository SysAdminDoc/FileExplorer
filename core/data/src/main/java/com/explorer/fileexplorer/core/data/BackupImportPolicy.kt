package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.ConnectionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The only data that can cross the portable-backup boundary. */
internal data class BackupPayload(
    val bookmarks: List<BackupBookmark>,
    val connections: List<BackupConnection>,
)

internal data class BackupBookmark(
    val name: String,
    val path: String,
    val sortOrder: Int,
) {
    fun toEntity(): BookmarkEntity = BookmarkEntity(
        name = name,
        path = path,
        sortOrder = sortOrder,
    )
}

/**
 * A connection deliberately contains only portable, non-secret metadata.
 * Passwords and private-key paths are kept in the local credential boundary and
 * are never read from or written to a backup.
 */
internal data class BackupConnection(
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val shareName: String,
    val remotePath: String,
    val useTls: Boolean,
) {
    fun toEntity(): ConnectionEntity = ConnectionEntity(
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        shareName = shareName,
        remotePath = remotePath,
        useTls = useTls,
    )

    fun identity(): BackupConnectionIdentity = BackupConnectionIdentity(
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        shareName = shareName,
        remotePath = remotePath,
        useTls = useTls,
    )
}

internal data class BackupConnectionIdentity(
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val shareName: String,
    val remotePath: String,
    val useTls: Boolean,
)

internal data class BackupImportPlan(
    val bookmarksToInsert: List<BackupBookmark>,
    val connectionsToInsert: List<BackupConnection>,
    val skippedBookmarks: Int,
    val skippedConnections: Int,
)

class BackupFormatException(message: String) : IllegalArgumentException(message)

/** Bounds and validates the untrusted portable-backup format before database access. */
internal object BackupImportPolicy {
    const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
    const val MAX_RECORDS = 2_048
    const val MAX_STRING_CHARS = 16_384

    private const val MAX_NAME_CHARS = 256
    private const val MAX_APP_CHARS = 32
    private const val MAX_PROTOCOL_CHARS = 32
    private const val MAX_HOST_CHARS = 4_096
    private const val MAX_USERNAME_CHARS = 256
    private const val MAX_SHARE_NAME_CHARS = 256
    private const val MAX_PORT = 65_535

    fun parse(input: InputStream): BackupPayload {
        val jsonText = input.readUtf8Bounded(MAX_IMPORT_BYTES)
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            throw BackupFormatException("Invalid backup JSON")
        }

        return try {
            val version = requiredInt(root, "version", 0, Int.MAX_VALUE)
            if (version != BackupSchema.VERSION) {
                throw BackupFormatException("Unsupported backup version")
            }
            if (requiredString(root, "app", MAX_APP_CHARS, allowBlank = false) != "FileExplorer") {
                throw BackupFormatException("Backup belongs to an unsupported application")
            }

            val bookmarks = parseBookmarks(root)
            val connections = parseConnections(root)
            if (bookmarks.size + connections.size > MAX_RECORDS) {
                throw BackupFormatException("Backup contains too many records")
            }
            BackupPayload(bookmarks = bookmarks, connections = connections)
        } catch (error: BackupFormatException) {
            throw error
        } catch (_: Exception) {
            throw BackupFormatException("Invalid backup payload")
        }
    }

    private fun parseBookmarks(root: JSONObject): List<BackupBookmark> {
        val array = optionalArray(root, "bookmarks") ?: return emptyList()
        requireRecordCount(array, "bookmarks")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val bookmark = array.get(index) as? JSONObject
                    ?: throw BackupFormatException("Bookmark record is not an object")
                add(
                    BackupBookmark(
                        name = requiredString(bookmark, "name", MAX_NAME_CHARS, allowBlank = false),
                        path = requiredString(bookmark, "path", MAX_STRING_CHARS, allowBlank = false),
                        sortOrder = optionalInt(bookmark, "sortOrder", 0, Int.MIN_VALUE, Int.MAX_VALUE),
                    ),
                )
            }
        }
    }

    private fun parseConnections(root: JSONObject): List<BackupConnection> {
        val array = optionalArray(root, "connections") ?: return emptyList()
        requireRecordCount(array, "connections")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val connection = array.get(index) as? JSONObject
                    ?: throw BackupFormatException("Connection record is not an object")
                rejectSecretFields(connection)
                add(
                    BackupConnection(
                        name = requiredString(connection, "name", MAX_NAME_CHARS, allowBlank = false),
                        protocol = requiredString(connection, "protocol", MAX_PROTOCOL_CHARS, allowBlank = false),
                        host = requiredString(connection, "host", MAX_HOST_CHARS, allowBlank = false),
                        port = requiredInt(connection, "port", 0, MAX_PORT),
                        username = optionalString(connection, "username", "", MAX_USERNAME_CHARS),
                        shareName = optionalString(connection, "shareName", "", MAX_SHARE_NAME_CHARS),
                        remotePath = optionalString(connection, "remotePath", "/", MAX_STRING_CHARS),
                        useTls = optionalBoolean(connection, "useTls", false),
                    ),
                )
            }
        }
    }

    private fun rejectSecretFields(connection: JSONObject) {
        if (connection.has("password") || connection.has("privateKeyPath")) {
            throw BackupFormatException("Credential fields are not supported in portable backups")
        }
    }

    private fun optionalArray(root: JSONObject, key: String): JSONArray? {
        if (!root.has(key)) return null
        if (root.isNull(key)) throw BackupFormatException("Backup field '$key' cannot be null")
        return root.get(key) as? JSONArray
            ?: throw BackupFormatException("Backup field '$key' is not an array")
    }

    private fun requireRecordCount(array: JSONArray, field: String) {
        if (array.length() > MAX_RECORDS) {
            throw BackupFormatException("Backup field '$field' contains too many records")
        }
    }

    private fun requiredString(
        objectValue: JSONObject,
        key: String,
        maxChars: Int,
        allowBlank: Boolean,
    ): String {
        if (!objectValue.has(key) || objectValue.isNull(key)) {
            throw BackupFormatException("Backup field '$key' is required")
        }
        val value = objectValue.get(key) as? String
            ?: throw BackupFormatException("Backup field '$key' is not a string")
        if (value.length > maxChars) {
            throw BackupFormatException("Backup field '$key' is too long")
        }
        if (!allowBlank && value.isBlank()) {
            throw BackupFormatException("Backup field '$key' cannot be blank")
        }
        return value
    }

    private fun optionalString(
        objectValue: JSONObject,
        key: String,
        defaultValue: String,
        maxChars: Int,
    ): String {
        if (!objectValue.has(key)) return defaultValue
        return requiredString(objectValue, key, maxChars, allowBlank = true)
    }

    private fun requiredInt(objectValue: JSONObject, key: String, min: Int, max: Int): Int {
        if (!objectValue.has(key) || objectValue.isNull(key)) {
            throw BackupFormatException("Backup field '$key' is required")
        }
        return intValue(objectValue.get(key), key, min, max)
    }

    private fun optionalInt(
        objectValue: JSONObject,
        key: String,
        defaultValue: Int,
        min: Int,
        max: Int,
    ): Int {
        if (!objectValue.has(key)) return defaultValue
        return intValue(objectValue.get(key), key, min, max)
    }

    private fun intValue(value: Any?, key: String, min: Int, max: Int): Int {
        val number = value as? Number
            ?: throw BackupFormatException("Backup field '$key' is not an integer")
        val longValue = number.toLong()
        if (number.toDouble() != longValue.toDouble() || longValue !in min.toLong()..max.toLong()) {
            throw BackupFormatException("Backup field '$key' is outside its allowed range")
        }
        return longValue.toInt()
    }

    private fun optionalBoolean(objectValue: JSONObject, key: String, defaultValue: Boolean): Boolean {
        if (!objectValue.has(key)) return defaultValue
        return objectValue.get(key) as? Boolean
            ?: throw BackupFormatException("Backup field '$key' is not a boolean")
    }

    private fun InputStream.readUtf8Bounded(maxBytes: Int): String {
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) {
                val next = read()
                if (next < 0) break
                if (total == maxBytes) throw BackupFormatException("Backup exceeds the size limit")
                bytes.write(next)
                total++
                continue
            }
            if (count > maxBytes - total) {
                throw BackupFormatException("Backup exceeds the size limit")
            }
            bytes.write(buffer, 0, count)
            total += count
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}

internal fun buildBackupImportPlan(
    payload: BackupPayload,
    existingBookmarks: List<BookmarkEntity>,
    existingConnections: List<ConnectionEntity>,
): BackupImportPlan {
    val knownBookmarkPaths = existingBookmarks.mapTo(mutableSetOf()) { it.path }
    val bookmarksToInsert = buildList(payload.bookmarks.size) {
        payload.bookmarks.forEach { bookmark ->
            if (knownBookmarkPaths.add(bookmark.path)) add(bookmark)
        }
    }

    val knownConnectionIdentities = existingConnections
        .mapTo(mutableSetOf()) { connection ->
            BackupConnection(
                name = connection.name,
                protocol = connection.protocol,
                host = connection.host,
                port = connection.port,
                username = connection.username,
                shareName = connection.shareName,
                remotePath = connection.remotePath,
                useTls = connection.useTls,
            ).identity()
        }
    val connectionsToInsert = buildList(payload.connections.size) {
        payload.connections.forEach { connection ->
            if (knownConnectionIdentities.add(connection.identity())) add(connection)
        }
    }

    return BackupImportPlan(
        bookmarksToInsert = bookmarksToInsert,
        connectionsToInsert = connectionsToInsert,
        skippedBookmarks = payload.bookmarks.size - bookmarksToInsert.size,
        skippedConnections = payload.connections.size - connectionsToInsert.size,
    )
}
