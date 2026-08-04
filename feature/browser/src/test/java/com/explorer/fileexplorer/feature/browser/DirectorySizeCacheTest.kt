package com.explorer.fileexplorer.feature.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectorySizeCacheTest {
    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val cache = DirectorySizeCache(maxEntries = 2)
        cache.put("/first", 1L)
        cache.put("/second", 2L)
        assertEquals(1L, cache.get("/first"))

        cache.put("/third", 3L)

        assertNull(cache.get("/second"))
        assertEquals(1L, cache.get("/first"))
        assertEquals(3L, cache.get("/third"))
    }

    @Test
    fun negativeSizesAreNormalized() {
        val cache = DirectorySizeCache()
        cache.put("/folder", -1L)

        assertEquals(0L, cache.get("/folder"))
    }
}
