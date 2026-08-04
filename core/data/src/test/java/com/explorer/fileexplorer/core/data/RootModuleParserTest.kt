package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootModuleParserTest {

    @Test
    fun parsesMetadataAndStatusMarkers() {
        val module = RootModuleParser.parse(
            modulePath = "/data/adb/modules/example.module",
            manager = RootModuleManager.KERNELSU,
            moduleProp = """
                id=example.module
                name=Example Module
                version=2.4.1
                versionCode=20401
                author=Example Author
                description=Adds a useful feature
            """.trimIndent(),
            disabled = true,
            pendingRemoval = true,
            skipMount = false,
        )

        requireNotNull(module)
        assertEquals("example.module", module.id)
        assertEquals("Example Module", module.name)
        assertEquals(20401, module.versionCode)
        assertEquals(RootModuleManager.KERNELSU, module.manager)
        assertFalse(module.enabled)
        assertTrue(module.pendingRemoval)
    }

    @Test
    fun preservesEqualsInDescriptionAndFallsBackForMissingOptionalMetadata() {
        val module = RootModuleParser.parse(
            modulePath = "/data/adb/modules/minimal",
            manager = RootModuleManager.MAGISK,
            moduleProp = "id=minimal\ndescription=a=b=c",
            disabled = false,
            pendingRemoval = false,
            skipMount = true,
        )

        requireNotNull(module)
        assertEquals("minimal", module.name)
        assertEquals("Unknown version", module.version)
        assertEquals("Unknown author", module.author)
        assertEquals("a=b=c", module.description)
        assertTrue(module.enabled)
        assertTrue(module.skipMount)
    }

    @Test
    fun rejectsInvalidIdsAndPaths() {
        assertFalse(RootModuleParser.isValidModuleId("1starts-with-number"))
        assertFalse(RootModuleParser.isValidModuleId("has space"))
        assertNull(
            RootModuleParser.parse(
                modulePath = "/data/adb/modules/../outside",
                manager = RootModuleManager.APATCH,
                moduleProp = "id=outside",
                disabled = false,
                pendingRemoval = false,
                skipMount = false,
            ),
        )
    }
}
