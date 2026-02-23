package com.explorer.fileexplorer.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY sort_order ASC, name ASC")
    fun getAllFlow(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY sort_order ASC, name ASC")
    suspend fun getAll(): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path)")
    suspend fun exists(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("UPDATE bookmarks SET sort_order = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY accessed_at DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 100): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files ORDER BY accessed_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<RecentFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recent: RecentFileEntity): Long

    @Query("DELETE FROM recent_files WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()

    @Transaction
    suspend fun upsert(recent: RecentFileEntity) {
        deleteByPath(recent.path)
        insert(recent)
    }
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT :limit")
    fun getHistoryFlow(limit: Int = 20): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntity): Long

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteByQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Transaction
    suspend fun upsert(entry: SearchHistoryEntity) {
        deleteByQuery(entry.query)
        insert(entry)
    }
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM network_connections ORDER BY last_connected DESC")
    fun getAllFlow(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM network_connections ORDER BY last_connected DESC")
    suspend fun getAll(): List<ConnectionEntity>

    @Query("SELECT * FROM network_connections WHERE id = :id")
    suspend fun getById(id: Long): ConnectionEntity?

    @Query("SELECT * FROM network_connections WHERE protocol = :protocol ORDER BY last_connected DESC")
    fun getByProtocolFlow(protocol: String): Flow<List<ConnectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Delete
    suspend fun delete(connection: ConnectionEntity)

    @Query("DELETE FROM network_connections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE network_connections SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long = System.currentTimeMillis())
}
