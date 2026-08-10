package com.explorer.fileexplorer.core.data

import androidx.room.withTransaction
import com.explorer.fileexplorer.core.database.AppDatabase
import com.explorer.fileexplorer.core.database.BookmarkDao
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.ConnectionDao
import com.explorer.fileexplorer.core.database.ConnectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    companion object {
        const val VERSION = BackupSchema.VERSION
    }

    suspend fun exportToStream(out: OutputStream, includeConnections: Boolean = false) = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("app", "FileExplorer")

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
        try {
            // Parsing and validation are complete before this transaction is opened.
            val payload = BackupImportPolicy.parse(input)
            val summary = database.withTransaction {
                val plan = buildBackupImportPlan(
                    payload = payload,
                    existingBookmarks = bookmarkDao.getAll(),
                    existingConnections = connectionDao.getAll(),
                )
                plan.bookmarksToInsert.forEach { bookmarkDao.insert(it.toEntity()) }
                plan.connectionsToInsert.forEach { connectionDao.insert(it.toEntity()) }
                BackupSummary(
                    bookmarks = plan.bookmarksToInsert.size,
                    connections = plan.connectionsToInsert.size,
                    skippedBookmarks = plan.skippedBookmarks,
                    skippedConnections = plan.skippedConnections,
                )
            }
            Result.success(summary)
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

internal object BackupSchema {
    const val VERSION = 1
}

data class BackupSummary(
    val bookmarks: Int,
    val connections: Int,
    val skippedBookmarks: Int = 0,
    val skippedConnections: Int = 0,
)
