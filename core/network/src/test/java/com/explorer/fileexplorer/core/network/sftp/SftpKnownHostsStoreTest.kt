package com.explorer.fileexplorer.core.network.sftp

import java.nio.file.Files
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SftpKnownHostsStoreTest {
    @Test
    fun verifierRaisesChallengeForUnknownHostKeyThenVerifiesAfterTrust() {
        val knownHostsFile = tempKnownHostsFile()
        val store = SftpKnownHostsStore(knownHostsFile, testOnly = true)
        val publicKey = generatePublicKey()
        val verifier = store.createVerifier()

        val error = assertFailsWith<SftpHostKeyVerificationException> {
            verifier.verify("example.test", 22, publicKey)
        }

        assertEquals("example.test", error.challenge.hostname)
        assertEquals(22, error.challenge.port)
        assertTrue(error.challenge.fingerprintSha256.startsWith("SHA256:"))
        assertEquals(false, error.challenge.isChangedKey)

        store.trust(error.challenge)

        assertTrue(verifier.verify("example.test", 22, publicKey))
    }

    @Test
    fun verifierRaisesChangedKeyChallengeWhenStoredHostKeyDiffers() {
        val knownHostsFile = tempKnownHostsFile()
        val store = SftpKnownHostsStore(knownHostsFile, testOnly = true)
        val originalKey = generatePublicKey()
        val changedKey = generatePublicKey()

        val originalChallenge = assertFailsWith<SftpHostKeyVerificationException> {
            store.createVerifier().verify("example.test", 22, originalKey)
        }.challenge
        store.trust(originalChallenge)

        val changedChallenge = assertFailsWith<SftpHostKeyVerificationException> {
            store.createVerifier().verify("example.test", 22, changedKey)
        }.challenge

        assertTrue(changedChallenge.isChangedKey)
    }

    @Test
    fun trustWritesOpenSshHostMarkerForNonDefaultPort() {
        val knownHostsFile = tempKnownHostsFile()
        val store = SftpKnownHostsStore(knownHostsFile, testOnly = true)
        val publicKey = generatePublicKey()

        val challenge = assertFailsWith<SftpHostKeyVerificationException> {
            store.createVerifier().verify("example.test", 2222, publicKey)
        }.challenge
        store.trust(challenge)

        assertTrue(knownHostsFile.readText().contains("[example.test]:2222 ${challenge.keyAlgorithm} "))
        assertTrue(store.createVerifier().verify("example.test", 2222, publicKey))
    }

    private fun tempKnownHostsFile() = Files.createTempDirectory("fileexplorer-known-hosts")
        .resolve("known_hosts")
        .toFile()

    private fun generatePublicKey() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()
        .public
}
