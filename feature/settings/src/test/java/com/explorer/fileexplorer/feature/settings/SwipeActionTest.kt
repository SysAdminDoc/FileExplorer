package com.explorer.fileexplorer.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeActionTest {
    @Test
    fun unknownStoredValueFallsBackToNoAction() {
        assertEquals(SwipeAction.NONE, SwipeAction.fromKey("future-action"))
        assertEquals(SwipeAction.NONE, SwipeAction.fromKey(null))
    }

    @Test
    fun storedNamesRoundTrip() {
        SwipeAction.entries.forEach { action ->
            assertEquals(action, SwipeAction.fromKey(action.name))
        }
    }
}
