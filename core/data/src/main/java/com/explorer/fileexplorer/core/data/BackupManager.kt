package com.explorer.fileexplorer.core.data

import androidx.room.withTransaction
import com.explorer.fileexplorer.core.database.AppDatabase
import com.explorer.fileexplorer.core.database.BookmarkDao
import com.explorer.fileexplorer.core.database.ConnectionDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val connectionDao: ConnectionDao,
    private val database: AppDatabase,
    private val settingsStore: PortableSettingsStore,
) {
    companion object {
        const val VERSION = BackupSchema.VERSION
    }

    private val importMutex = Mutex()

    suspend fun exportToStream(out: OutputStream, includeConnections: Boolean = false) = withContext(Dispatchers.IO) {
        val settings = settingsStore.readPortableSettings()
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("app", "FileExplorer")
        root.put("settings", settings.toJson())

        val bookmarks = JSONArray()
        for (b in bookmarkDao.getAll()) {
            bookmarks.put(JSONObject().apply {
                put("name", b.name)
                put("path", b.path)
                put("sortOrder", b.sortOrder)
            })
        }
        root.put("bookmarks", bookmarks)

        if (includeConnections) {
            val connections = JSONArray()
            for (c in connectionDao.getAll()) {
                connections.put(JSONObject().apply {
                    // Only non-secret connection metadata is portable.
                    put("name", c.name)
                    put("protocol", c.protocol)
                    put("host", c.host)
                    put("port", c.port)
                    put("username", c.username)
                    put("shareName", c.shareName)
                    put("remotePath", c.remotePath)
                    put("useTls", c.useTls)
                })
            }
            root.put("connections", connections)
        }

        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
    }

    suspend fun importFromStream(input: InputStream): Result<BackupSummary> = withContext(Dispatchers.IO) {
        val prepared = prepareImport(input)
        if (prepared.isFailure) return@withContext Result.failure(prepared.exceptionOrNull()!!)
        importPrepared(prepared.getOrThrow())
    }

    /** Parses and validates a backup, and computes its duplicate summary without mutation. */
    suspend fun prepareImport(input: InputStream): Result<PreparedBackup> = withContext(Dispatchers.IO) {
        try {
            val payload = BackupImportPolicy.parse(input)
            val preview = database.withTransaction {
                buildBackupImportPlan(
                    payload = payload,
                    existingBookmarks = bookmarkDao.getAll(),
                    existingConnections = connectionDao.getAll(),
                ).toPreview()
            }
            Result.success(PreparedBackup(payload, preview))
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Applies a previously validated backup with rollback compensation for DataStore failures. */
    suspend fun importPrepared(prepared: PreparedBackup): Result<BackupSummary> = withContext(Dispatchers.IO) {
        importMutex.withLock {
            var previousSettings: PortableSettings? = null
            try {
                if (prepared.payload.settings != null) {
                    previousSettings = settingsStore.readPortableSettings()
                }
                val summary = database.withTransaction {
                    val plan = buildBackupImportPlan(
                        payload = prepared.payload,
                        existingBookmarks = bookmarkDao.getAll(),
                        existingConnections = connectionDao.getAll(),
                    )
                    plan.settingsToApply?.let { settings -> settingsStore.replacePortableSettings(settings) }
                    plan.bookmarksToInsert.forEach { bookmarkDao.insert(it.toEntity()) }
                    plan.connectionsToInsert.forEach { connectionDao.insert(it.toEntity()) }
                    plan.toSummary()
                }
                Result.success(summary)
            } catch (error: CancellationException) {
                restoreSettings(previousSettings, error)
                throw error
            } catch (error: Exception) {
                restoreSettings(previousSettings, error)
                Result.failure(error)
            }
        }
    }

    private suspend fun restoreSettings(previous: PortableSettings?, error: Throwable) {
        if (previous == null) return
        withContext(NonCancellable) {
            runCatching { settingsStore.replacePortableSettings(previous) }
                .onFailure(error::addSuppressed)
        }
    }

}

internal object BackupSchema {
    const val VERSION = 2
    val SUPPORTED_VERSIONS: Set<Int> = setOf(1, VERSION)
}

data class BackupPreview(
    val bookmarks: Int,
    val connections: Int,
    val settings: Int,
    val skippedBookmarks: Int,
    val skippedConnections: Int,
)

class PreparedBackup internal constructor(
    internal val payload: BackupPayload,
    val preview: BackupPreview,
)

data class BackupSummary(
    val bookmarks: Int,
    val connections: Int,
    val settings: Int = 0,
    val skippedBookmarks: Int = 0,
    val skippedConnections: Int = 0,
)

private fun PortableSettings.toJson(): JSONObject = JSONObject().apply {
    put("showHidden", showHidden)
    put("foldersFirst", foldersFirst)
    put("confirmDelete", confirmDelete)
    put("defaultView", defaultView)
    put("sortField", sortField)
    put("sortDirection", sortDirection)
    put("thumbnailSize", thumbnailSize)
    put("thumbnailCacheSizeMb", thumbnailCacheSizeMb)
    put("thumbnailCacheLocation", thumbnailCacheLocation)
    put("themeMode", themeMode)
    put("trashTtlDays", trashTtlDays)
    put("compactDensity", compactDensity)
    put("showDirectorySizes", showDirectorySizes)
    put("swipeLeftAction", swipeLeftAction)
    put("swipeRightAction", swipeRightAction)
}

private fun BackupImportPlan.toPreview(): BackupPreview = BackupPreview(
    bookmarks = bookmarksToInsert.size,
    connections = connectionsToInsert.size,
    settings = if (settingsToApply == null) 0 else 1,
    skippedBookmarks = skippedBookmarks,
    skippedConnections = skippedConnections,
)

private fun BackupImportPlan.toSummary(): BackupSummary = BackupSummary(
    bookmarks = bookmarksToInsert.size,
    connections = connectionsToInsert.size,
    settings = if (settingsToApply == null) 0 else 1,
    skippedBookmarks = skippedBookmarks,
    skippedConnections = skippedConnections,
)
