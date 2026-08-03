package com.explorer.fileexplorer.feature.browser

/**
 * Guards pane transfers from copying or moving a directory into itself.
 * Paths are intentionally treated as opaque provider paths; only the path
 * separator relationship is relevant for local and URI-backed repositories.
 */
object DropPathPolicy {

    fun canDrop(sourcePath: String, destinationPath: String): Boolean {
        val source = normalize(sourcePath)
        val destination = normalize(destinationPath)
        if (source == destination) return false
        return source == "/" || !destination.startsWith("$source/")
    }

    private fun normalize(path: String): String {
        if (path.isEmpty()) return path
        return if (path.length > 1) path.trimEnd('/') else path
    }
}
