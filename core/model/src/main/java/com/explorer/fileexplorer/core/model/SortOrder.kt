package com.explorer.fileexplorer.core.model

enum class SortField {
    NAME, SIZE, DATE, TYPE
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

data class SortOrder(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true,
)

enum class ViewMode {
    LIST, GRID
}

enum class FileColumn(val label: String) {
    SIZE("Size"),
    DATE("Modified"),
    TYPE("Type"),
    ;

    companion object {
        val DEFAULT_VISIBLE_COLUMNS: Set<FileColumn> = setOf(SIZE, DATE)
    }
}
