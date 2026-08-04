package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbPathCodecTest {
    @Test
    fun roundTripsTreeUriAndChildNames() {
        val root = UsbPathCodec.rootPath("content://com.android.externalstorage.documents/tree/ABCD-1234%3A")
        val child = UsbPathCodec.childPath(root, "Photos & videos")
        val nested = UsbPathCodec.childPath(child, "birthday/photo 01.jpg")

        assertTrue(UsbPathCodec.isUsbPath(nested))
        assertEquals("content://com.android.externalstorage.documents/tree/ABCD-1234%3A", UsbPathCodec.treeUriString(nested))
        assertEquals(listOf("Photos & videos", "birthday/photo 01.jpg"), UsbPathCodec.segments(nested))
        assertEquals(child, UsbPathCodec.parentPath(nested))
        assertEquals("birthday/photo 01.jpg", UsbPathCodec.name(nested))
    }

    @Test
    fun rootHasNoParentOrName() {
        val root = UsbPathCodec.rootPath("content://usb/tree/root")

        assertNull(UsbPathCodec.parentPath(root))
        assertNull(UsbPathCodec.name(root))
        assertEquals(emptyList(), UsbPathCodec.segments(root))
    }

    @Test
    fun rejectsNonUsbPaths() {
        assertTrue(!UsbPathCodec.isUsbPath("/storage/emulated/0"))
        assertNull(UsbPathCodec.treeUriString("/storage/emulated/0"))
    }
}
