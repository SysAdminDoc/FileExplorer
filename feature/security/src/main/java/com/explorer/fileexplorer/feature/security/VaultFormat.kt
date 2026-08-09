package com.explorer.fileexplorer.feature.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VaultEntry(
    val id: String,
    val displayName: String,
    val size: Long,
    val modifiedAt: Long,
)

internal data class VaultIndexRecord(
    val id: String,
    val originalName: String,
    val size: Long,
    val modifiedAt: Long,
)

class VaultSession internal constructor(
    internal val key: SecretKey,
) {
    @Volatile
    private var unlocked = true

    internal fun requireUnlocked() {
        check(unlocked) { "Vault is locked" }
    }

    fun isUnlocked(): Boolean = unlocked

    internal fun lock() {
        unlocked = false
    }
}

internal object VaultFilePolicy {
    const val PAYLOAD_SUFFIX = ".vault"
    private const val MAX_NAME_BYTES = 4096
    private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun validateId(id: String) {
        require(ID_PATTERN.matches(id)) { "Invalid vault entry id" }
    }

    fun validateName(name: String) {
        require(name.isNotBlank()) { "Vault filename is empty" }
        require(name != "." && name != "..") { "Invalid vault filename" }
        require(!name.contains('/') && !name.contains('\\')) { "Invalid vault filename" }
        require(name.none(Char::isISOControl)) { "Invalid vault filename" }
        require(name.toByteArray(StandardCharsets.UTF_8).size <= MAX_NAME_BYTES) {
            "Vault filename is too long"
        }
    }

    fun validateRecord(record: VaultIndexRecord) {
        validateId(record.id)
        validateName(record.originalName)
        require(record.size >= 0) { "Invalid vault file size" }
        require(record.modifiedAt >= 0) { "Invalid vault modification time" }
    }

    fun payloadName(id: String): String {
        validateId(id)
        return id + PAYLOAD_SUFFIX
    }
}

internal object VaultIndexCodec {
    private val MAGIC = byteArrayOf(0x46, 0x45, 0x56, 0x49)
    private const val VERSION = 1
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val MAX_ENTRIES = 4096
    private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
    private const val MAX_NAME_BYTES = 4096
    private const val MAX_ID_BYTES = 64

    fun encode(records: List<VaultIndexRecord>, key: SecretKey): ByteArray {
        require(records.size <= MAX_ENTRIES) { "Too many vault entries" }
        val plaintext = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(records.size)
                records.forEach { record ->
                    VaultFilePolicy.validateRecord(record)
                    writeString(output, record.id, MAX_ID_BYTES)
                    writeString(output, record.originalName, MAX_NAME_BYTES)
                    output.writeLong(record.size)
                    output.writeLong(record.modifiedAt)
                }
            }
        }.toByteArray()

        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            doFinal(plaintext)
        }
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(MAGIC)
                output.writeByte(VERSION)
                output.write(iv)
                output.write(ciphertext)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray, key: SecretKey): List<VaultIndexRecord> {
        require(bytes.size <= MAX_INDEX_BYTES) { "Vault index is too large" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Unsupported vault index" }
        require(input.readUnsignedByte() == VERSION) { "Unsupported vault index version" }
        val iv = ByteArray(IV_SIZE)
        input.readFully(iv)
        val ciphertext = readRemaining(input, MAX_INDEX_BYTES)
        require(ciphertext.size >= 16) { "Truncated vault index" }
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            doFinal(ciphertext)
        }
        val records = DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            val count = data.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid vault entry count" }
            buildList(count) {
                repeat(count) {
                    val record = VaultIndexRecord(
                        id = readString(data, MAX_ID_BYTES),
                        originalName = readString(data, MAX_NAME_BYTES),
                        size = data.readLong(),
                        modifiedAt = data.readLong(),
                    )
                    VaultFilePolicy.validateRecord(record)
                    add(record)
                }
            }
        }
        require(records.map { it.id }.toSet().size == records.size) { "Duplicate vault entry id" }
        return records
    }

    private fun writeString(output: DataOutputStream, value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Invalid vault index string" }
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readUnsignedShort()
        require(length in 1..maxBytes) { "Invalid vault index string" }
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8).also {
            require(!it.contains('\uFFFD')) { "Invalid vault index text" }
        }
    }

    private fun readRemaining(input: InputStream, maxBytes: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "Vault index is too large" }
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }
}

internal object VaultPayloadFormat {
    private val MAGIC = byteArrayOf(0x46, 0x45, 0x56, 0x50)
    private const val VERSION = 1
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val BUFFER_SIZE = 64 * 1024

    fun encrypt(source: File, destination: File, key: SecretKey) {
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Only regular local files can enter the vault"
        }
        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        FileOutputStream(destination, false).use { output ->
            output.write(MAGIC)
            output.write(VERSION)
            output.write(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
            FileInputStream(source).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    cipher.update(buffer, 0, read)?.let(output::write)
                }
            }
            cipher.doFinal()?.let(output::write)
            output.fd.sync()
        }
    }

    fun decrypt(source: File, destination: File, key: SecretKey) {
        FileInputStream(source).use { input ->
            val magic = ByteArray(MAGIC.size).also { readFully(input, it) }
            require(magic.contentEquals(MAGIC)) { "Unsupported vault payload" }
            require(input.read() == VERSION) { "Unsupported vault payload version" }
            val iv = ByteArray(IV_SIZE).also { readFully(input, it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
            FileOutputStream(destination, false).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    cipher.update(buffer, 0, read)?.let(output::write)
                }
                cipher.doFinal()?.let(output::write)
                output.fd.sync()
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("Truncated vault payload")
            if (read == 0) continue
            offset += read
        }
    }
}
