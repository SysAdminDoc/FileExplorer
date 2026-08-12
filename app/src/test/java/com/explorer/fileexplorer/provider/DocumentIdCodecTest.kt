package com.explorer.fileexplorer.provider

import java.nio.file.Paths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentIdCodecTest {

    @Test
    fun roundTripPreservesAbsolutePaths() {
        val path = "/storage/emulated/0/Photos/party image #1.jpg"

        val encoded = DocumentIdCodec.encode(path)

        assertEquals(path, DocumentIdCodec.decode(encoded))
    }

    @Test
    fun invalidIdsAreRejected() {
        assertNull(DocumentIdCodec.decode("not valid base64!"))
    }

    @Test
    fun pathPolicyRejectsEscapesAndTheRootItselfAsAChild() {
        val root = Paths.get("/storage/emulated/0")

        assertTrue(DocumentPathPolicy.isWithinRoot(root.resolve("Pictures"), root))
        assertFalse(DocumentPathPolicy.isWithinRoot(root.resolve("../data").normalize(), root))
        assertTrue(DocumentPathPolicy.isChild(root, root.resolve("Pictures")))
        assertFalse(DocumentPathPolicy.isChild(root, root))
    }

    @Test
    fun pathPolicyRejectsSymlinkSegments() {
        val root = Files.createTempDirectory("documents-provider-policy")
        try {
            val target = Files.createDirectory(root.resolve("target"))
            val link = root.resolve("link")
            try {
                Files.createSymbolicLink(link, target)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: SecurityException) {
                return
            }

            assertTrue(DocumentPathPolicy.containsSymlink(link, root))
            assertFalse(DocumentPathPolicy.isSafePath(link, root))
            assertTrue(DocumentPathPolicy.isSafePath(target, root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
