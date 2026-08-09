package com.explorer.fileexplorer.feature.security

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultFormatTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
    private val wrongKey = SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")
    private val record = VaultIndexRecord(
        id = "00000000-0000-4000-8000-000000000001",
        originalName = "private-notes.txt",
        size = 42,
        modifiedAt = 1_725_000_000_000,
    )

    @Test
    fun `locked session rejects vault work and unlocked session permits it`() {
        val session = VaultSession(key)

        assertTrue(session.isUnlocked())
        session.requireUnlocked()
        session.lock()
        assertFalse(session.isUnlocked())
        assertFailsWith<IllegalStateException> { session.requireUnlocked() }
    }

    @Test
    fun `index round trip keeps names out of the on disk envelope`() {
        val encoded = VaultIndexCodec.encode(listOf(record), key)

        assertFalse(String(encoded, StandardCharsets.ISO_8859_1).contains(record.originalName))
        assertEquals(listOf(record), VaultIndexCodec.decode(encoded, key))
    }

    @Test
    fun `corrupt index is rejected`() {
        val encoded = VaultIndexCodec.encode(listOf(record), key)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()

        assertFailsWith<Exception> { VaultIndexCodec.decode(encoded, key) }
    }

    @Test
    fun `index encrypted with another key is rejected`() {
        val encoded = VaultIndexCodec.encode(listOf(record), key)

        assertFailsWith<Exception> { VaultIndexCodec.decode(encoded, wrongKey) }
    }

    @Test
    fun `payload round trips and tampering is rejected`() {
        val directory = Files.createTempDirectory("fileexplorer-vault-test")
        try {
            val source = directory.resolve("source.bin").toFile().also {
                it.writeBytes(ByteArray(100_003) { (it * 13).toByte() })
            }
            val encrypted = directory.resolve("opaque.vault").toFile()
            val restored = directory.resolve("restored.bin").toFile()

            VaultPayloadFormat.encrypt(source, encrypted, key)
            VaultPayloadFormat.decrypt(encrypted, restored, key)
            assertContentEquals(source.readBytes(), restored.readBytes())

            val tampered = encrypted.readBytes()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
            encrypted.writeBytes(tampered)
            assertFailsWith<Exception> {
                VaultPayloadFormat.decrypt(encrypted, directory.resolve("bad.bin").toFile(), key)
            }
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }
}
