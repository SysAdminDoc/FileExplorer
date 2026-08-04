package com.explorer.fileexplorer.feature.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DexHeaderReaderTest {
    @Test
    fun readsLittleEndianMethodCount() {
        val header = ByteArray(92)
        header[0] = 'd'.code.toByte()
        header[1] = 'e'.code.toByte()
        header[2] = 'x'.code.toByte()
        header[88] = 0x78
        header[89] = 0x56
        header[90] = 0x34
        header[91] = 0x12

        assertEquals(0x12345678, DexHeaderReader.methodCount(header))
    }

    @Test
    fun rejectsNonDexHeaders() {
        assertNull(DexHeaderReader.methodCount(ByteArray(92)))
        assertNull(DexHeaderReader.methodCount(ByteArray(8).also { it[0] = 'd'.code.toByte() }))
    }
}
