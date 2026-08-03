package com.explorer.fileexplorer.feature.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropPathPolicyTest {

    @Test
    fun rejectsDroppingIntoTheSameDirectory() {
        assertFalse(DropPathPolicy.canDrop("/storage/emulated/0/Photos", "/storage/emulated/0/Photos"))
        assertFalse(DropPathPolicy.canDrop("/storage/emulated/0/Photos/", "/storage/emulated/0/Photos/"))
    }

    @Test
    fun rejectsDroppingDirectoryIntoDescendant() {
        assertFalse(
            DropPathPolicy.canDrop(
                "/storage/emulated/0/Photos",
                "/storage/emulated/0/Photos/Trips/2026",
            ),
        )
    }

    @Test
    fun allowsSiblingAndOpaqueProviderPaths() {
        assertTrue(
            DropPathPolicy.canDrop(
                "/storage/emulated/0/Photos",
                "/storage/emulated/0/Videos",
            ),
        )
        assertTrue(DropPathPolicy.canDrop("sftp://nas/share/a.txt", "sftp://nas/share/archive"))
    }

    @Test
    fun rootCanBeDroppedToAnyNonRootFolder() {
        assertTrue(DropPathPolicy.canDrop("/", "/storage/emulated/0"))
    }
}
