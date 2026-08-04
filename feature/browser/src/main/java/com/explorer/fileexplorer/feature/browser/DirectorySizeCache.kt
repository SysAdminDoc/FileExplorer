package com.explorer.fileexplorer.feature.browser

/** Small access-ordered cache for directory sizes so revisiting a folder is cheap. */
class DirectorySizeCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = object : LinkedHashMap<String, Long>(maxEntries, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(path: String): Long? = entries[path]

    @Synchronized
    fun put(path: String, size: Long) {
        entries[path] = size.coerceAtLeast(0L)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        private const val LOAD_FACTOR = 0.75f
    }
}
