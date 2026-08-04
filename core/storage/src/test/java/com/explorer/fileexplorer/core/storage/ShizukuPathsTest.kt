package com.explorer.fileexplorer.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShizukuPathsTest {
    @Test
    fun `allows Android data and obb descendants only`() {
        assertTrue(ShizukuPaths.isAllowed(ShizukuPaths.ANDROID_DATA_ROOT))
        assertTrue(ShizukuPaths.isAllowed("${ShizukuPaths.ANDROID_DATA_ROOT}/com.example/cache"))
        assertTrue(ShizukuPaths.isAllowed(ShizukuPaths.ANDROID_OBB_ROOT))
        assertTrue(ShizukuPaths.isAllowed("${ShizukuPaths.ANDROID_OBB_ROOT}/com.example"))
        assertFalse(ShizukuPaths.isAllowed("/storage/emulated/0/Android/data/../obb"))
        assertFalse(ShizukuPaths.isAllowed("/storage/emulated/0/Android/data2"))
        assertTrue(ShizukuPaths.isProtectedRoot(ShizukuPaths.ANDROID_DATA_ROOT))
        assertTrue(ShizukuPaths.isProtectedRoot(ShizukuPaths.ANDROID_OBB_ROOT))
        assertFalse(ShizukuPaths.isProtectedRoot("${ShizukuPaths.ANDROID_DATA_ROOT}/com.example"))
    }

    @Test
    fun `rejects unsafe child names and quotes shell arguments`() {
        assertNull(ShizukuPaths.child(ShizukuPaths.ANDROID_DATA_ROOT, "../obb"))
        assertNull(ShizukuPaths.child(ShizukuPaths.ANDROID_DATA_ROOT, ""))
        assertEquals("'a'\\''b'", ShizukuPaths.shellQuote("a'b"))
        assertTrue(ShizukuPaths.isAllowed(ShizukuPaths.child(ShizukuPaths.ANDROID_DATA_ROOT, "a'b")!!))
    }
}
