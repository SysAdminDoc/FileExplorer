package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShizukuLsParserTest {

    @Test
    fun `toybox listing keeps a name that contains spaces`() {
        val entry = ShizukuLsParser.parse(
            "drwxrwx--x 3 u0_a123 ext_data_rw 3452 2026-09-14 10:22 My Game Saves/",
        )!!
        assertEquals("My Game Saves", entry.name)
        assertTrue(entry.isDirectory)
        assertEquals("u0_a123", entry.owner)
        assertEquals("ext_data_rw", entry.group)
        assertEquals(3452L, entry.size)
    }

    @Test
    fun `toybox listing parses a plain file`() {
        val entry = ShizukuLsParser.parse(
            "-rw-rw---- 1 u0_a123 ext_data_rw 1024 2026-09-14 10:22 config.json",
        )!!
        assertEquals("config.json", entry.name)
        assertEquals(1024L, entry.size)
    }

    @Test
    fun `gnu style listing keeps a name that contains spaces`() {
        val entry = ShizukuLsParser.parse(
            "-rw-r--r-- 1 shell shell 12 Sep 14 10:22 two words.txt",
        )!!
        assertEquals("two words.txt", entry.name)
    }

    @Test
    fun `symlink keeps its target`() {
        val entry = ShizukuLsParser.parse(
            "lrwxrwxrwx 1 u0_a123 ext_data_rw 9 2026-09-14 10:22 my link -> some target",
        )!!
        assertTrue(entry.isSymlink)
        assertEquals("my link", entry.name)
        assertEquals("some target", entry.symlinkTarget)
    }

    @Test
    fun `single entry listing of a path keeps only the last segment`() {
        val entry = ShizukuLsParser.parse(
            "drwxrwx--x 2 u0_a123 ext_data_rw 3452 2026-09-14 10:22 /storage/emulated/0/Android/data/com.example/New folder",
            nameIsPath = true,
        )!!
        assertEquals("New folder", entry.name)
    }

    @Test
    fun `total line and malformed lines are ignored`() {
        assertNull(ShizukuLsParser.parse("total 24"))
        assertNull(ShizukuLsParser.parse(""))
        assertNull(ShizukuLsParser.parse("ls: /storage/emulated/0/Android/data: Permission denied"))
    }
}
