package com.explorer.fileexplorer.feature.browser

internal object TabIndexPolicy {

    fun selectedIndexAfterClose(selectedIndex: Int, closedIndex: Int, lastIndex: Int): Int {
        return when {
            closedIndex < selectedIndex -> selectedIndex - 1
            closedIndex == selectedIndex -> selectedIndex.coerceAtMost(lastIndex)
            else -> selectedIndex
        }
    }

    fun moveSelectedIndex(selectedIndex: Int, fromIndex: Int, toIndex: Int): Int {
        return when {
            selectedIndex == fromIndex -> toIndex
            fromIndex < selectedIndex && toIndex >= selectedIndex -> selectedIndex - 1
            fromIndex > selectedIndex && toIndex <= selectedIndex -> selectedIndex + 1
            else -> selectedIndex
        }
    }
}
