package com.explorer.fileexplorer.feature.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileEncryptionEngineTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun `encrypted payload round trips with original filename`() {
        val plaintext = ByteArray(128 * 1024 + 17) { (it * 31).toByte() }
        val encrypted = encrypt(plaintext, "résumé.txt")
        val decrypted = ByteArrayOutputStream()

        val originalName = FileEncryptionEngine.decrypt(
            ByteArrayInputStream(encrypted),
            decrypted,
            key,
        )

        assertEquals("résumé.txt", originalName)
        assertContentEquals(plaintext, decrypted.toByteArray())
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val encrypted = encrypt("secret contents".toByteArray(), "secret.txt")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()

        assertFailsWith<Exception> {
            FileEncryptionEngine.decrypt(
                ByteArrayInputStream(encrypted),
                ByteArrayOutputStream(),
                key,
            )
        }
    }

    @Test
    fun `invalid header names are rejected before decryption`() {
        val encrypted = encrypt("contents".toByteArray(), "safe.txt")
        val nameStart = 4 + 1 + 2
        encrypted[nameStart] = '/'.code.toByte()

        assertFailsWith<IllegalArgumentException> {
            FileEncryptionEngine.readHeader(ByteArrayInputStream(encrypted))
        }
    }

    private fun encrypt(plaintext: ByteArray, name: String): ByteArray {
        val encrypted = ByteArrayOutputStream()
        FileEncryptionEngine.encrypt(
            ByteArrayInputStream(plaintext),
            encrypted,
            name,
            key,
        )
        return encrypted.toByteArray()
    }
}
