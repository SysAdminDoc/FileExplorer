package com.explorer.fileexplorer.provider

import java.nio.file.Path

internal object DocumentPathPolicy {

    fun isWithinRoot(candidate: Path, root: Path): Boolean =
        candidate == root || candidate.startsWith(root)

    fun isChild(parent: Path, child: Path): Boolean =
        child != parent && child.startsWith(parent)
}
