package com.explorer.fileexplorer.core.network.sftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SftpFileRepositoryTest {
    @Test
    fun remoteNamesWithShellCharactersRemainLiteralPathSegments() {
        val name = "quote' ; \"glob* [x] $newline 日本語"
        val source = "/remote/$name"

        assertEquals(name, SftpRemotePath.fileName(source))
        assertEquals("/target/$name", SftpRemotePath.child("/target", name))
        assertEquals("/target", SftpRemotePath.parent("/target/$name"))
    }

    @Test
    fun descendantProtectionUsesPathSegments() {
        assertTrue(SftpRemotePath.isSameOrDescendant("/remote/folder", "/remote/folder"))
        assertTrue(SftpRemotePath.isSameOrDescendant("/remote/folder", "/remote/folder/sub"))
        assertFalse(SftpRemotePath.isSameOrDescendant("/remote/folder", "/remote/folder-copy"))
        assertFalse(SftpRemotePath.isSameOrDescendant("/remote/folder", "/remote/other"))
    }

    @Test
    fun invalidRootAndDotNamesFailClosed() {
        assertFailsWith<IllegalArgumentException> { SftpRemotePath.fileName("/") }
        assertFailsWith<IllegalArgumentException> { SftpRemotePath.fileName(".") }
        assertFailsWith<IllegalArgumentException> { SftpRemotePath.fileName("/remote/..") }
    }

    private val newline = "\n"
}
