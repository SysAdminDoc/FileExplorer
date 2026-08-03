package com.explorer.fileexplorer.provider

import java.nio.file.Paths
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
}
