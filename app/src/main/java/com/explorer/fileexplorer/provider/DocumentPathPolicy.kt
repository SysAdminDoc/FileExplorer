package com.explorer.fileexplorer.provider

import java.nio.file.Path
import java.nio.file.Files

internal object DocumentPathPolicy {

    fun isWithinRoot(candidate: Path, root: Path): Boolean {
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        return normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith(normalizedRoot)
    }

    fun isChild(parent: Path, child: Path): Boolean {
        val normalizedParent = parent.toAbsolutePath().normalize()
        val normalizedChild = child.toAbsolutePath().normalize()
        return normalizedChild != normalizedParent && normalizedChild.startsWith(normalizedParent)
    }

    fun containsSymlink(candidate: Path, root: Path): Boolean {
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!isWithinRoot(normalizedCandidate, normalizedRoot)) return true
        var current = normalizedRoot
        if (Files.isSymbolicLink(current)) return true
        for (part in normalizedRoot.relativize(normalizedCandidate)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    fun isSafePath(candidate: Path, root: Path): Boolean =
        isWithinRoot(candidate, root) &&
            !containsSymlink(candidate, root) &&
            runCatching {
                val canonicalCandidate = candidate.toFile().canonicalFile.toPath()
                val canonicalRoot = root.toFile().canonicalFile.toPath()
                isWithinRoot(canonicalCandidate, canonicalRoot)
            }.getOrDefault(false)
}
