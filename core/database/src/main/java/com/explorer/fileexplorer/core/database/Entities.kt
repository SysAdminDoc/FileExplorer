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

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "file_tags",
    primaryKeys = ["path", "tag_name"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["name"],
            childColumns = ["tag_name"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_name"]), Index(value = ["path"])],
)
data class FileTagEntity(
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "tag_name") val tagName: String,
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

@Entity(tableName = "recent_locations")
data class RecentLocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "scope_path") val scopePath: String? = null,
    @ColumnInfo(name = "searched_at") val searchedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "saved_searches",
    indices = [Index(value = ["name"], unique = true)],
)
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "scope_path") val scopePath: String,
    @ColumnInfo(name = "use_regex") val useRegex: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "network_connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "protocol") val protocol: String, // smb, sftp, ftp, webdav
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int,
    @ColumnInfo(name = "username") val username: String = "",
    @ColumnInfo(name = "password") val password: String = "",
    @ColumnInfo(name = "share_name") val shareName: String = "", // SMB share
    @ColumnInfo(name = "remote_path") val remotePath: String = "/",
    @ColumnInfo(name = "private_key_path") val privateKeyPath: String = "", // SFTP key auth
    @ColumnInfo(name = "use_tls") val useTls: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_connected") val lastConnected: Long = 0L,
)

@Entity(tableName = "directory_view_preferences")
data class DirectoryViewPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "view_mode") val viewMode: String = "LIST",
    @ColumnInfo(name = "sort_field") val sortField: String = "NAME",
    @ColumnInfo(name = "sort_direction") val sortDirection: String = "ASCENDING",
    @ColumnInfo(name = "folders_first") val foldersFirst: Boolean = true,
    @ColumnInfo(name = "visible_columns") val visibleColumns: String = "SIZE,DATE",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "integrity_entries")
data class IntegrityEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "is_directory") val isDirectory: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long? = null,
    @ColumnInfo(name = "status") val status: String = "OK",
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@Entity(tableName = "transfer_tasks")
data class TransferTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "queue_order") val queueOrder: Int,
    @ColumnInfo(name = "operation") val operation: String,
    @ColumnInfo(name = "source_paths") val sourcePaths: String,
    @ColumnInfo(name = "destination") val destination: String,
    @ColumnInfo(name = "bandwidth_limit_bytes_per_second") val bandwidthLimitBytesPerSecond: Long,
    @ColumnInfo(name = "conflict_action") val conflictAction: String?,
    @ColumnInfo(name = "apply_conflict_to_all") val applyConflictToAll: Boolean,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "transferred_bytes") val transferredBytes: Long,
    @ColumnInfo(name = "completed_sources") val completedSources: Int,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
    @ColumnInfo(name = "current_file") val currentFile: String,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "conflict_source_path") val conflictSourcePath: String?,
    @ColumnInfo(name = "conflict_destination_path") val conflictDestinationPath: String?,
    @ColumnInfo(name = "conflict_is_text") val conflictIsText: Boolean,
    @ColumnInfo(name = "conflict_diff_preview") val conflictDiffPreview: String,
    @ColumnInfo(name = "conflict_source_size") val conflictSourceSize: Long?,
    @ColumnInfo(name = "conflict_destination_size") val conflictDestinationSize: Long?,
    @ColumnInfo(name = "conflict_source_modified") val conflictSourceModified: Long?,
    @ColumnInfo(name = "conflict_destination_modified") val conflictDestinationModified: Long?,
    @ColumnInfo(name = "conflict_source_is_directory") val conflictSourceIsDirectory: Boolean,
    @ColumnInfo(name = "conflict_destination_is_directory") val conflictDestinationIsDirectory: Boolean,
    @ColumnInfo(name = "conflict_planned_keep_both_path") val conflictPlannedKeepBothPath: String?,
    @ColumnInfo(name = "conflict_decisions") val conflictDecisions: String,
    @ColumnInfo(name = "intended_entries") val intendedEntries: String,
    @ColumnInfo(name = "committed_entries") val committedEntries: String,
    @ColumnInfo(name = "recovery_policy") val recoveryPolicy: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
