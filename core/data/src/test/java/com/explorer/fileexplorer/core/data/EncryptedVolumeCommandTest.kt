package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedVolumeCommandTest {
    @Test
    fun normalizesAbsolutePathsAndTrailingSlash() {
        assertEquals(
            "/storage/emulated/0/vault",
            EncryptedVolumePathPolicy.normalize(" /storage/emulated/0/vault/// ").getOrThrow(),
        )
    }

    @Test
    fun rejectsRelativeRootAndControlCharacterPaths() {
        assertTrue(EncryptedVolumePathPolicy.normalize("vault").isFailure)
        assertTrue(EncryptedVolumePathPolicy.normalize("/").isFailure)
        assertTrue(EncryptedVolumePathPolicy.normalize("/storage/vault\n").isFailure)
        assertTrue(EncryptedVolumePathPolicy.normalize("/storage/vault\u0000").isFailure)
    }

    @Test
    fun quotesShellMetacharactersWithoutChangingTheirValue() {
        assertEquals("'/storage/it'\\''s vault'", EncryptedVolumeCommandBuilder.shellQuote("/storage/it's vault"))
        assertEquals("'/storage/\$HOME;rm -rf /'", EncryptedVolumeCommandBuilder.shellQuote("/storage/\$HOME;rm -rf /"))
    }

    @Test
    fun commandsUsePassphraseFileAndNeverEmbedPassphrase() {
        val request = EncryptedVolumeRequest(
            format = EncryptedVolumeFormat.GOCRYPTFS,
            cipherPath = "/storage/cipher",
            mountPath = "/mnt/secure",
            readOnly = true,
        )

        val command = EncryptedVolumeCommandBuilder.mountCommand(request, "/data/user/0/app/cache/pass file")

        assertTrue(command.contains("-passfile '/data/user/0/app/cache/pass file'"))
        assertTrue(command.contains("-ro"))
        assertFalse(command.contains("secret"))
    }

    @Test
    fun encfsCommandQuotesExternalPasswordCommand() {
        val request = EncryptedVolumeRequest(
            format = EncryptedVolumeFormat.ENCFS,
            cipherPath = "/storage/cipher",
            mountPath = "/mnt/secure",
        )

        val command = EncryptedVolumeCommandBuilder.mountCommand(request, "/data/user/0/app/cache/pass file")

        assertTrue(command.startsWith("encfs --extpass='cat '\\''/data/user/0/app/cache/pass file'\\'''"))
        assertTrue(command.contains("-o allow_other"))
    }

    @Test
    fun unmountFallsBackToUmount() {
        assertEquals(
            "fusermount -u '/mnt/secure' 2>/dev/null || umount '/mnt/secure' 2>/dev/null",
            EncryptedVolumeCommandBuilder.unmountCommand("/mnt/secure"),
        )
    }
}
