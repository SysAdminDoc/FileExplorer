package com.explorer.fileexplorer.core.database

import com.explorer.fileexplorer.core.model.FileColumn
import com.explorer.fileexplorer.core.model.SortDirection
import com.explorer.fileexplorer.core.model.SortField
import com.explorer.fileexplorer.core.model.SortOrder
import com.explorer.fileexplorer.core.model.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectoryViewPreferenceCodecTest {
    @Test
    fun encodeAndDecodeRoundTripPreservesFolderPreferences() {
        val entity = DirectoryViewPreferenceCodec.encode(
            path = "/storage/emulated/0/Documents",
            viewMode = ViewMode.GRID,
            sortOrder = SortOrder(
                field = SortField.SIZE,
                direction = SortDirection.DESCENDING,
                foldersFirst = false,
            ),
            visibleColumns = setOf(FileColumn.TYPE),
            updatedAt = 123L,
        )

        assertEquals("/storage/emulated/0/Documents", entity.path)
        assertEquals("TYPE", entity.visibleColumns)
        assertEquals(123L, entity.updatedAt)
        assertEquals(
            DirectoryViewPreferenceSnapshot(
                viewMode = ViewMode.GRID,
                sortOrder = SortOrder(SortField.SIZE, SortDirection.DESCENDING, foldersFirst = false),
                visibleColumns = setOf(FileColumn.TYPE),
            ),
            DirectoryViewPreferenceCodec.decode(entity),
        )
    }

    @Test
    fun decodeUsesSafeDefaultsForUnknownEnumValues() {
        val decoded = DirectoryViewPreferenceCodec.decode(
            DirectoryViewPreferenceEntity(
                path = "/broken",
                viewMode = "FUTURE_MODE",
                sortField = "FUTURE_FIELD",
                sortDirection = "FUTURE_DIRECTION",
                visibleColumns = "FUTURE_COLUMN",
            ),
        )

        assertEquals(DirectoryViewPreferenceSnapshot(), decoded)
    }

    @Test
    fun decodePreservesAnExplicitlyEmptyColumnSet() {
        val decoded = DirectoryViewPreferenceCodec.decode(
            DirectoryViewPreferenceEntity(path = "/minimal", visibleColumns = ""),
        )

        assertEquals(emptySet(), decoded.visibleColumns)
    }
}
