package com.explorer.fileexplorer.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.explorer.fileexplorer.core.designsystem.*
import com.explorer.fileexplorer.core.model.FileItem

@Composable
fun FileIcon(
    item: FileItem,
    modifier: Modifier = Modifier,
) {
    val (icon, tint) = getFileIconAndColor(item)
    Icon(
        imageVector = icon,
        contentDescription = if (item.isDirectory) "Folder" else "File: ${item.extension}",
        tint = tint,
        modifier = modifier,
    )
}

fun getFileIconAndColor(item: FileItem): Pair<ImageVector, Color> {
    return when {
        item.isDirectory -> Icons.Filled.Folder to ColorFolder
        item.isSymlink -> Icons.Filled.Link to AccentPurple
        item.isApk -> Icons.Filled.Android to ColorApk
        item.isImage -> Icons.Filled.Image to ColorImage
        item.isVideo -> Icons.Filled.VideoFile to ColorVideo
        item.isAudio -> Icons.Filled.AudioFile to ColorAudio
        item.isArchive -> Icons.Filled.FolderZip to ColorArchive
        item.isText -> Icons.Filled.Description to ColorCode
        item.mimeType.contains("pdf") -> Icons.Filled.PictureAsPdf to AccentRed
        item.mimeType.contains("spreadsheet") || item.extension in setOf("xlsx", "xls", "csv") ->
            Icons.Filled.TableChart to AccentGreen
        item.mimeType.contains("presentation") || item.extension in setOf("pptx", "ppt") ->
            Icons.Filled.Slideshow to AccentOrange
        item.mimeType.contains("document") || item.extension in setOf("docx", "doc") ->
            Icons.Filled.Article to ColorDocument
        item.extension in setOf("db", "sqlite", "sqlite3") -> Icons.Filled.Storage to AccentPurple
        item.extension in setOf("json", "xml", "yaml", "yml", "toml") ->
            Icons.Filled.DataObject to ColorCode
        else -> Icons.Filled.InsertDriveFile to ColorDefault
    }
}
