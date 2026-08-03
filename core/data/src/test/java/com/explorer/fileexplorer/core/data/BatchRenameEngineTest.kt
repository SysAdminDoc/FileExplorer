package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.FileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchRenameEngineTest {

    @Test
    fun expandsCommonTokensAndPreservesExtension() {
        val items = listOf(
            file("IMG_0001.jpg", "/photos/IMG_0001.jpg", 1_700_000_000_000L),
            file("IMG_0002.jpg", "/photos/IMG_0002.jpg", 1_700_000_000_000L),
        )

        val preview = BatchRenameEngine.preview(
            items,
            BatchRenameOptions(
                template = "{parent}_{counter}_{date}{ext}",
                counterPadding = 2,
                datePattern = "yyyy",
            ),
        )

        assertTrue(preview.isValid)
        assertEquals("photos_01_2023.jpg", preview.items[0].newName)
        assertEquals("/photos/photos_02_2023.jpg", preview.items[1].targetPath)
    }

    @Test
    fun expandsRegexCaptureGroups() {
        val preview = BatchRenameEngine.preview(
            listOf(file("trip-001.jpg", "/photos/trip-001.jpg")),
            BatchRenameOptions(
                template = "shot_{group1}_{counter}{ext}",
                regex = "trip-(\\d+)",
                counterPadding = 0,
            ),
        )

        assertTrue(preview.isValid)
        assertEquals("shot_001_1.jpg", preview.items.single().newName)
    }

    @Test
    fun rejectsInvalidAndDuplicateTargets() {
        val items = listOf(file("a.txt", "/tmp/a.txt"), file("b.txt", "/tmp/b.txt"))

        val duplicate = BatchRenameEngine.preview(items, BatchRenameOptions(template = "same.txt"))
        assertFalse(duplicate.isValid)
        assertEquals(setOf("Duplicate target name"), duplicate.errors.toSet())

        val invalid = BatchRenameEngine.preview(items, BatchRenameOptions(template = "bad/name"))
        assertFalse(invalid.isValid)
        assertTrue(invalid.errors.single().contains("path separator"))
    }

    @Test
    fun rejectsUnknownTokenAndNonMatchingPattern() {
        val item = file("a.txt", "/tmp/a.txt")

        val unknown = BatchRenameEngine.preview(listOf(item), BatchRenameOptions(template = "{missing}"))
        assertFalse(unknown.isValid)
        assertTrue(unknown.errors.single().contains("Unknown token"))

        val noMatch = BatchRenameEngine.preview(
            listOf(item),
            BatchRenameOptions(template = "{group1}{ext}", regex = "b-(\\d+)"),
        )
        assertFalse(noMatch.isValid)
        assertTrue(noMatch.errors.single().contains("does not match"))
    }

    private fun file(name: String, path: String, lastModified: Long = 0L) = FileItem(
        name = name,
        path = path,
        lastModified = lastModified,
    )
}
