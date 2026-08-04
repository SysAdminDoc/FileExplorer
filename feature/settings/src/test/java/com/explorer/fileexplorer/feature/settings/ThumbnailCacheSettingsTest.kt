package com.explorer.fileexplorer.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailCacheSettingsTest {
    @Test
    fun sizeIsBoundedToSupportedRange() {
        assertEquals(ThumbnailCacheSettings.MIN_SIZE_MB, ThumbnailCacheSettings.normalizeSize(Int.MIN_VALUE))
        assertEquals(ThumbnailCacheSettings.DEFAULT_SIZE_MB, ThumbnailCacheSettings.normalizeSize(ThumbnailCacheSettings.DEFAULT_SIZE_MB))
        assertEquals(ThumbnailCacheSettings.MAX_SIZE_MB, ThumbnailCacheSettings.normalizeSize(Int.MAX_VALUE))
    }

    @Test
    fun unknownLocationFallsBackToInternal() {
        assertEquals(ThumbnailCacheLocation.INTERNAL, ThumbnailCacheLocation.fromKey(null))
        assertEquals(ThumbnailCacheLocation.INTERNAL, ThumbnailCacheLocation.fromKey("future-location"))
    }
}
