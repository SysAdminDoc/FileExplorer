package com.explorer.fileexplorer.feature.network

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

internal class ShareServerPathResolver(rootPath: String) {

    val root: Path = File(rootPath).canonicalFile.toPath()

    fun resolve(current: Path, requested: String): Path? {
        val normalized = requested.replace('\\', '/')
        val candidate = try {
            if (normalized.startsWith('/')) {
                root.resolve(normalized.removePrefix("/"))
            } else {
                current.resolve(normalized)
            }.toFile().canonicalFile.toPath()
        } catch (_: Exception) {
            return null
        }
        return candidate.takeIf { isWithinRoot(it) }
    }

    fun resolveFromRoot(requested: String): Path? = resolve(root, requested)

    fun isWithinRoot(candidate: Path): Boolean =
        candidate == root || candidate.startsWith(root)

    fun displayPath(path: Path): String {
        if (!isWithinRoot(path)) return "/"
        val relative = root.relativize(path)
        if (relative.nameCount == 0) return "/"
        return "/" + relative.joinToString("/")
    }

    fun relativePath(path: Path): String? {
        if (!isWithinRoot(path)) return null
        return root.relativize(path).toString().replace(File.separatorChar, '/')
    }

    fun pathFromRelative(relative: String): Path? =
        resolve(root, Paths.get(relative).toString())
}
