package com.explorer.fileexplorer.feature.browser

/** Width policy shared by the adaptive browser surface and its JVM tests. */
internal object LargeScreenLayoutPolicy {
    const val THREE_PANE_MIN_WIDTH_DP = 840

    fun useThreePane(widthDp: Int): Boolean = widthDp >= THREE_PANE_MIN_WIDTH_DP
}
