package com.explorer.fileexplorer.core.data

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArchiveEntryPathPolicyTest {
    @Test
    fun allowsNestedEntryInsideDestination() {
        val root = Files.createTempDirectory("fileexplorer-rar-policy")
        try {
            assertEquals(
                root.resolve("folder").resolve("file.txt").toFile().canonicalFile,
                ArchiveEntryPathPolicy.safeDestination(root.toString(), "folder/file.txt"),
            )
        } finally {
            root.deleteIfExists()
        }
    }

    @Test
    fun rejectsParentTraversalAndSiblingPrefixEscapes() {
        val root = Files.createTempDirectory("fileexplorer-rar-policy")
        try {
            assertNull(ArchiveEntryPathPolicy.safeDestination(root.toString(), "../outside.txt"))
            assertNull(ArchiveEntryPathPolicy.safeDestination(root.toString(), "../${root.fileName}-sibling/outside.txt"))
        } finally {
            root.deleteIfExists()
        }
    }
}
