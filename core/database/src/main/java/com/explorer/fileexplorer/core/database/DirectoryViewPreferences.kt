package com.explorer.fileexplorer.core.database

import com.explorer.fileexplorer.core.model.FileColumn
import com.explorer.fileexplorer.core.model.SortDirection
import com.explorer.fileexplorer.core.model.SortField
import com.explorer.fileexplorer.core.model.SortOrder
import com.explorer.fileexplorer.core.model.ViewMode

data class DirectoryViewPreferenceSnapshot(
    val viewMode: ViewMode = ViewMode.LIST,
    val sortOrder: SortOrder = SortOrder(),
    val visibleColumns: Set<FileColumn> = FileColumn.DEFAULT_VISIBLE_COLUMNS,
)

object DirectoryViewPreferenceCodec {
    fun encode(
        path: String,
        viewMode: ViewMode,
        sortOrder: SortOrder,
        visibleColumns: Set<FileColumn>,
        updatedAt: Long = System.currentTimeMillis(),
    ): DirectoryViewPreferenceEntity = DirectoryViewPreferenceEntity(
        path = path,
        viewMode = viewMode.name,
        sortField = sortOrder.field.name,
        sortDirection = sortOrder.direction.name,
        foldersFirst = sortOrder.foldersFirst,
        visibleColumns = visibleColumns
            .sortedBy(FileColumn::ordinal)
            .joinToString(",") { it.name },
        updatedAt = updatedAt,
    )

    fun decode(entity: DirectoryViewPreferenceEntity?): DirectoryViewPreferenceSnapshot {
        if (entity == null) return DirectoryViewPreferenceSnapshot()

        val visibleColumns = if (entity.visibleColumns.isEmpty()) {
            emptySet()
        } else {
            entity.visibleColumns.split(',')
                .mapNotNull { value -> FileColumn.entries.firstOrNull { it.name == value.trim() } }
                .toSet()
                .ifEmpty { FileColumn.DEFAULT_VISIBLE_COLUMNS }
        }
        return DirectoryViewPreferenceSnapshot(
            viewMode = ViewMode.entries.firstOrNull { it.name == entity.viewMode } ?: ViewMode.LIST,
            sortOrder = SortOrder(
                field = SortField.entries.firstOrNull { it.name == entity.sortField } ?: SortField.NAME,
                direction = SortDirection.entries.firstOrNull { it.name == entity.sortDirection }
                    ?: SortDirection.ASCENDING,
                foldersFirst = entity.foldersFirst,
            ),
            visibleColumns = visibleColumns,
        )
    }
}
