package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import kotlinx.coroutines.flow.Flow

enum class SecureDeleteCapability {
    BEST_EFFORT,
    UNSUPPORTED,
}

data class DeleteCapabilities(
    val supportsPermanentDelete: Boolean = true,
    val secureDelete: SecureDeleteCapability = SecureDeleteCapability.UNSUPPORTED,
    val secureDeleteDescription: String = "This provider can delete the item but cannot verify overwrite-based erasure.",
) {
    companion object {
        val LOCAL_BEST_EFFORT = DeleteCapabilities(
            secureDelete = SecureDeleteCapability.BEST_EFFORT,
            secureDeleteDescription = "Local overwrite is best effort and cannot guarantee erasure on flash storage, snapshots, or copy-on-write filesystems.",
        )

        val PROVIDER_DELETE_ONLY = DeleteCapabilities()
    }
}

/**
 * Abstract filesystem provider — analogous to PowerShell's PSDrive/Provider system.
 * Each implementation handles a different backend: local, root, SAF, SMB, SFTP, cloud, archive.
 */
interface FileRepository {

    /** Operations supported by this provider. Unsupported operations fail explicitly. */
    val capabilities: RepositoryCapabilities
        get() = RepositoryCapabilities.unsupported("unknown")

    /** Capabilities for irreversible deletion at this provider/location. */
    fun deleteCapabilities(paths: List<String>): DeleteCapabilities = DeleteCapabilities.PROVIDER_DELETE_ONLY

    /** List files in a directory. Returns Flow for streaming large directories. */
    fun listFiles(path: String): Flow<List<FileItem>>

    /** Get a single file's metadata. */
    suspend fun getFileInfo(path: String): FileItem?

    /** Check if a path exists. */
    suspend fun exists(path: String): Boolean

    /** Copy files to destination. Progress callback: (bytesTransferred, totalBytes, currentFile). */
    suspend fun copyFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
        conflictSuffix: String? = null,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Int>

    /** Move files to destination. Tries rename-in-place first, falls back to copy+delete. */
    suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
        conflictSuffix: String? = null,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Int>

    /** Delete files/directories recursively. */
    suspend fun deleteFiles(
        paths: List<String>,
        onProgress: (String) -> Unit = {},
    ): Result<Int>

    /** Create a new directory. */
    suspend fun createDirectory(path: String): Result<FileItem>

    /** Create a new empty file. */
    suspend fun createFile(path: String): Result<FileItem>

    /** Rename a file or directory. */
    suspend fun rename(path: String, newName: String): Result<FileItem>

    /** Calculate total size of files/directories recursively. */
    suspend fun calculateSize(paths: List<String>): Long

    /** Search for files matching query. Streams results as found. */
    fun search(
        rootPath: String,
        query: String,
        regex: Boolean = false,
        includeHidden: Boolean = false,
    ): Flow<FileItem>

    /** Get file hash (MD5, SHA-256, etc). */
    suspend fun getChecksum(path: String, algorithm: String = "SHA-256"): String
}
