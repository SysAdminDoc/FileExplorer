package com.explorer.fileexplorer.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.explorer.fileexplorer.core.model.FileItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestActivity>()

    @Test
    fun fileRowExposesActionsDescriptionAndTouchTargetInRtlLargeText() {
        val item = FileItem(
            name = "document.txt",
            path = "/document.txt",
            size = 1024,
            mimeType = "text/plain",
            extension = "txt",
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                MaterialTheme {
                    FileListItem(
                        item = item,
                        onClick = {},
                        onLongClick = {},
                        onSwipeLeft = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("document.txt").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("File: txt")).assertIsDisplayed()
        composeRule.onNode(hasClickAction())
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }
}
