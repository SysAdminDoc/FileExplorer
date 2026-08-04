package com.explorer.fileexplorer.feature.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LargeScreenLayoutPolicyTest {
    @Test
    fun `three pane layout starts at compact large screen width`() {
        assertFalse(LargeScreenLayoutPolicy.useThreePane(839))
        assertTrue(LargeScreenLayoutPolicy.useThreePane(840))
        assertTrue(LargeScreenLayoutPolicy.useThreePane(1200))
    }
}
