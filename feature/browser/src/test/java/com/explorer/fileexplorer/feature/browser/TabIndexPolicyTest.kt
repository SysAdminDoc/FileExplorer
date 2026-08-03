package com.explorer.fileexplorer.feature.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class TabIndexPolicyTest {

    @Test
    fun closingTabKeepsSelectionStable() {
        assertEquals(1, TabIndexPolicy.selectedIndexAfterClose(selectedIndex = 2, closedIndex = 0, lastIndex = 3))
        assertEquals(2, TabIndexPolicy.selectedIndexAfterClose(selectedIndex = 2, closedIndex = 2, lastIndex = 3))
        assertEquals(1, TabIndexPolicy.selectedIndexAfterClose(selectedIndex = 1, closedIndex = 3, lastIndex = 3))
    }

    @Test
    fun closingLastSelectedTabSelectsPreviousTab() {
        assertEquals(1, TabIndexPolicy.selectedIndexAfterClose(selectedIndex = 2, closedIndex = 2, lastIndex = 1))
    }

    @Test
    fun reorderingUpdatesSelectionForMovedTab() {
        assertEquals(2, TabIndexPolicy.moveSelectedIndex(selectedIndex = 0, fromIndex = 0, toIndex = 2))
        assertEquals(0, TabIndexPolicy.moveSelectedIndex(selectedIndex = 1, fromIndex = 0, toIndex = 2))
        assertEquals(2, TabIndexPolicy.moveSelectedIndex(selectedIndex = 1, fromIndex = 2, toIndex = 0))
    }
}
