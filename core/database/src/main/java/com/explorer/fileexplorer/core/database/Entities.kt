package com.explorer.fileexplorer.core.database

import androidx.room.*

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "mime_type") val mimeType: String = "",
    @ColumnInfo(name = "size") val size: Long = 0L,
    @ColumnInfo(name = "is_directory") val isDirectory: Boolean = false,
    @ColumnInfo(name = "accessed_at") val accessedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "scope_path") val scopePath: String? = null,
    @ColumnInfo(name = "searched_at") val searchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "network_connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "protocol") val protocol: String, // smb, sftp, ftp, webdav
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int,
    @ColumnInfo(name = "username") val username: String = "",
    @ColumnInfo(name = "password") val password: String = "", // TODO: encrypt via Android Keystore
    @ColumnInfo(name = "share_name") val shareName: String = "", // SMB share
    @ColumnInfo(name = "remote_path") val remotePath: String = "/",
    @ColumnInfo(name = "private_key_path") val privateKeyPath: String = "", // SFTP key auth
    @ColumnInfo(name = "use_tls") val useTls: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_connected") val lastConnected: Long = 0L,
)
