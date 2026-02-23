package com.explorer.fileexplorer.core.model

import android.net.Uri
import java.nio.file.attribute.PosixFilePermission

/**
 * Universal file representation used across all providers
 * (local, root, SAF, network, cloud, archive).
 */
data class FileItem(
    val name: String,
    val path: String,
    val absolutePath: String = path,
    val uri: Uri? = null,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val isDirectory: Boolean = false,
    val isHidden: Boolean = false,
    val isSymlink: Boolean = false,
    val isReadable: Boolean = true,
    val isWritable: Boolean = true,
    val mimeType: String = if (isDirectory) "inode/directory" else "application/octet-stream",
    val extension: String = name.substringAfterLast('.', ""),
    val permissions: Set<PosixFilePermission> = emptySet(),
    val ownerName: String? = null,
    val groupName: String? = null,
    val symlinkTarget: String? = null,
    val childCount: Int? = null,
) {
    val isArchive: Boolean
        get() = extension.lowercase() in ARCHIVE_EXTENSIONS

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/")

    val isApk: Boolean
        get() = extension.equals("apk", ignoreCase = true)

    val isText: Boolean
        get() = mimeType.startsWith("text/") || extension.lowercase() in TEXT_EXTENSIONS

    val displaySize: String
        get() = when {
            isDirectory -> childCount?.let { "$it items" } ?: ""
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
            size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
            else -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
        }

    companion object {
        val ARCHIVE_EXTENSIONS = setOf(
            "zip", "7z", "tar", "gz", "bz2", "xz", "rar", "zst",
            "tgz", "tbz2", "txz", "jar", "war", "ear"
        )
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "toml", "ini", "cfg",
            "conf", "log", "csv", "tsv", "sh", "bash", "zsh", "fish",
            "py", "kt", "kts", "java", "js", "ts", "html", "css", "scss",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
            "gradle", "properties", "gitignore", "env", "dockerfile"
        )
    }
}
