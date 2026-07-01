package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RootShellEscapeTest {

    private fun esc(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    @Test
    fun plainPathIsUnchanged() {
        assertEquals("'/data/local/tmp'", esc("/data/local/tmp"))
    }

    @Test
    fun pathWithSpaces() {
        assertEquals("'/data/my folder/file.txt'", esc("/data/my folder/file.txt"))
    }

    @Test
    fun pathWithSingleQuote() {
        assertEquals("'/data/it'\\''s here'", esc("/data/it's here"))
    }

    @Test
    fun pathWithSemicolon() {
        assertEquals("'/data/foo;rm -rf /'", esc("/data/foo;rm -rf /"))
    }

    @Test
    fun pathWithDollarSign() {
        assertEquals("'/data/\$HOME'", esc("/data/\$HOME"))
    }

    @Test
    fun pathWithBackticks() {
        assertEquals("'/data/`whoami`'", esc("/data/`whoami`"))
    }

    @Test
    fun pathWithNewline() {
        assertEquals("'/data/line1\nline2'", esc("/data/line1\nline2"))
    }

    @Test
    fun pathWithGlobChars() {
        assertEquals("'/data/*.txt'", esc("/data/*.txt"))
    }
}
