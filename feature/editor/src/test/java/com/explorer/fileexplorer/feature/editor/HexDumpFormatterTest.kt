package com.explorer.fileexplorer.feature.editor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HexDumpFormatterTest {
    @Test
    fun formatsHexAndPrintableColumns() {
        val lines = HexDumpFormatter.format(byteArrayOf(0x00, 0x41, 0x7f, 0x20), startOffset = 16L)

        assertEquals(1, lines.size)
        assertEquals(16L, lines.single().offset)
        assertEquals("00 41 7F 20" + "   ".repeat(12), lines.single().hex)
        assertEquals(".A. ", lines.single().ascii)
    }

    @Test
    fun parsesExactHexBytes() {
        assertContentEquals(byteArrayOf(0x01, 0xAF.toByte(), 0x00), HexDumpFormatter.parseHexBytes("01 af 00"))
        assertNull(HexDumpFormatter.parseHexBytes("01 ff0"))
        assertNull(HexDumpFormatter.parseHexBytes("01 gg"))
    }
}
