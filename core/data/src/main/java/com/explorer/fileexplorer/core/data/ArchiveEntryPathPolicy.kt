package com.explorer.fileexplorer.core.data

import java.io.File

internal object ArchiveEntryPathPolicy {
    fun safeDestination(destination: String, entryName: String): File? {
        val root = File(destination).canonicalFile
        val output = File(root, entryName).canonicalFile
        return output.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }
}
