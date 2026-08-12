package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.model.ConflictNamePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ConflictNamePolicyTest {
    @Test
    fun suffixAndNameAreStableForTheSameDelivery() {
        val first = ConflictNamePolicy.suffix("job-7", "/source/report.txt", "/target/report.txt")
        val second = ConflictNamePolicy.suffix("job-7", "/source/report.txt", "/target/report.txt")

        assertEquals(first, second)
        assertNotEquals(first, ConflictNamePolicy.suffix("job-8", "/source/report.txt", "/target/report.txt"))
        assertEquals("report (copy-$first).txt", ConflictNamePolicy.fileName("report.txt", first))
        assertEquals("archive (copy-$first)", ConflictNamePolicy.fileName("archive", first))
    }

    @Test
    fun pathPreservesTheParentAndExtension() {
        assertEquals(
            "/target/report (copy-abc123).txt",
            ConflictNamePolicy.pathWithName("/target/report.txt", "abc123"),
        )
    }
}
