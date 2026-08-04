package com.explorer.fileexplorer.feature.browser

import com.explorer.fileexplorer.core.model.FileItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastMediaPolicyTest {

    @Test
    fun acceptsMediaMimeTypes() {
        assertTrue(FileItem(name = "photo.bin", path = "/photo.bin", mimeType = "image/jpeg").let(CastMediaPolicy::isCastable))
        assertTrue(FileItem(name = "track.bin", path = "/track.bin", mimeType = "audio/mpeg").let(CastMediaPolicy::isCastable))
        assertTrue(FileItem(name = "clip.bin", path = "/clip.bin", mimeType = "video/mp4").let(CastMediaPolicy::isCastable))
    }

    @Test
    fun acceptsKnownMediaExtensionsWhenMimeTypeIsGeneric() {
        assertTrue(
            CastMediaPolicy.isCastable(
                FileItem(name = "clip.MP4", path = "/clip.MP4", mimeType = "application/octet-stream"),
            ),
        )
    }

    @Test
    fun rejectsDirectoriesAndNonMediaFiles() {
        assertFalse(FileItem(name = "Pictures", path = "/Pictures", isDirectory = true).let(CastMediaPolicy::isCastable))
        assertFalse(
            CastMediaPolicy.isCastable(
                FileItem(name = "notes.txt", path = "/notes.txt", mimeType = "text/plain"),
            ),
        )
    }
}
