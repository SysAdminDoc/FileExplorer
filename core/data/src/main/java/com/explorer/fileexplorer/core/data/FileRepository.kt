package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import kotlinx.coroutines.flow.Flow

/**
 * Abstract filesystem provider — analogous to PowerShell's PSDrive/Provider system.
 * Each implementation handles a different backend: local, root, SAF, SMB, SFTP, cloud, archive.
 */
interface FileRepository {

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
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Int>

    /** Move files to destination. Tries rename-in-place first, falls back to copy+delete. */
    suspend fun moveFiles(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
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
