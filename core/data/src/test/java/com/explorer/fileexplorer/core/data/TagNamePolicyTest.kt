package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TagNamePolicyTest {
    @Test
    fun normalizesWhitespaceAndCase() {
        assertEquals("project files", TagNamePolicy.normalize("  PROJECT   files ").getOrThrow())
    }

    @Test
    fun rejectsBlankNames() {
        assertFailsWith<IllegalArgumentException> {
            TagNamePolicy.normalize("   ").getOrThrow()
        }
    }

    @Test
    fun rejectsNamesOverTheLimit() {
        assertFailsWith<IllegalArgumentException> {
            TagNamePolicy.normalize("x".repeat(TagNamePolicy.MAX_LENGTH + 1)).getOrThrow()
        }
    }
}
