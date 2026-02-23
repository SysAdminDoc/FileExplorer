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
