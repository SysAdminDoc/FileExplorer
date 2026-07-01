package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.database.BookmarkDao
import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.ConnectionDao
import com.explorer.fileexplorer.core.database.ConnectionEntity
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
) {
    companion object {
        const val VERSION = 1
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
            val json = JSONObject(input.bufferedReader().readText())
            val version = json.optInt("version", 0)
            if (version < 1) return@withContext Result.failure(Exception("Invalid backup format"))

            var bookmarksImported = 0
            var connectionsImported = 0

            val bookmarks = json.optJSONArray("bookmarks")
            if (bookmarks != null) {
                for (i in 0 until bookmarks.length()) {
                    val b = bookmarks.getJSONObject(i)
                    val path = b.getString("path")
                    if (!bookmarkDao.exists(path)) {
                        bookmarkDao.insert(BookmarkEntity(
                            name = b.getString("name"),
                            path = path,
                            sortOrder = b.optInt("sortOrder", 0),
                        ))
                        bookmarksImported++
                    }
                }
            }

            val connections = json.optJSONArray("connections")
            if (connections != null) {
                for (i in 0 until connections.length()) {
                    val c = connections.getJSONObject(i)
                    connectionDao.insert(ConnectionEntity(
                        name = c.getString("name"),
                        protocol = c.getString("protocol"),
                        host = c.getString("host"),
                        port = c.getInt("port"),
                        username = c.optString("username", ""),
                        shareName = c.optString("shareName", ""),
                        remotePath = c.optString("remotePath", "/"),
                        useTls = c.optBoolean("useTls", false),
                    ))
                    connectionsImported++
                }
            }

            Result.success(BackupSummary(bookmarksImported, connectionsImported))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class BackupSummary(val bookmarks: Int, val connections: Int)
