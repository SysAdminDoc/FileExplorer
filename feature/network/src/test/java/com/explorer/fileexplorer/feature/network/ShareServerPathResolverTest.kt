package com.explorer.fileexplorer.feature.network

import java.nio.file.Files
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareServerPathResolverTest {

    private val resolver = ShareServerPathResolver(
        Files.createTempDirectory("share-server-test").toFile().canonicalPath,
    )

    @After
    fun removeTemporaryRoot() {
        Files.deleteIfExists(resolver.root)
    }

    @Test
    fun resolvesAbsoluteAndRelativePathsInsideTheShare() {
        val photos = resolver.resolveFromRoot("/Pictures")
        val music = resolver.resolve(resolver.root.resolve("Pictures"), "../Music")

        assertEquals(resolver.root.resolve("Pictures"), photos)
        assertEquals(resolver.root.resolve("Music"), music)
        assertEquals("/Pictures", resolver.displayPath(photos!!))
    }

    @Test
    fun rejectsTraversalOutsideTheShare() {
        val escaped = resolver.resolve(resolver.root, "../../data")
        val temporaryUpload = resolver.resolveFromRoot("/.fileexplorer-upload-stale.tmp")

        assertNull(escaped)
        assertNull(temporaryUpload)
        assertTrue(resolver.isWithinRoot(resolver.root))
    }
}
