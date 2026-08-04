package com.explorer.fileexplorer.feature.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import javax.crypto.spec.GCMParameterSpec

internal object FileEncryptionEngine {
    private val magic = byteArrayOf(0x46, 0x45, 0x4e, 0x43)
    private const val VERSION = 1
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val MAX_NAME_BYTES = 4096

    data class ParsedHeader(
        val originalName: String,
        val iv: ByteArray,
        val encoded: ByteArray,
    )

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        originalName: String,
        key: SecretKey,
    ) {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        val header = encodeHeader(originalName, iv)
        output.write(header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(header)
        }
        CipherOutputStream(output, cipher).use { cipherOutput ->
            input.copyTo(cipherOutput, BUFFER_SIZE)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey): String {
        val header = readHeader(input)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.iv))
            updateAAD(header.encoded)
        }
        CipherInputStream(input, cipher).use { cipherInput ->
            cipherInput.copyTo(output, BUFFER_SIZE)
        }
        return header.originalName
    }

    fun readHeader(input: InputStream): ParsedHeader {
        val encoded = ByteArrayOutputStream()
        val actualMagic = readFully(input, magic.size).also { encoded.write(it) }
        if (!actualMagic.contentEquals(magic)) throw IOException("Unsupported encrypted file")

        val version = readByte(input).also { encoded.write(it) }
        if (version != VERSION) throw IOException("Unsupported encrypted file version")

        val nameLengthBytes = readFully(input, 2).also { encoded.write(it) }
        val nameLength = (((nameLengthBytes[0].toInt() and 0xff) shl 8) or
            (nameLengthBytes[1].toInt() and 0xff))
        if (nameLength !in 1..MAX_NAME_BYTES) throw IOException("Invalid encrypted filename")

        val nameBytes = readFully(input, nameLength).also { encoded.write(it) }
        val originalName = String(nameBytes, StandardCharsets.UTF_8)
        validateName(originalName)

        val iv = readFully(input, IV_SIZE).also { encoded.write(it) }
        return ParsedHeader(originalName, iv, encoded.toByteArray())
    }

    private fun encodeHeader(originalName: String, iv: ByteArray): ByteArray {
        validateName(originalName)
        require(iv.size == IV_SIZE) { "Invalid encryption IV" }
        val nameBytes = originalName.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= MAX_NAME_BYTES) { "Filename is too long" }
        return ByteArrayOutputStream().apply {
            write(magic)
            write(VERSION)
            write((nameBytes.size shr 8) and 0xff)
            write(nameBytes.size and 0xff)
            write(nameBytes)
            write(iv)
        }.toByteArray()
    }

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "Encrypted filename is empty" }
        require(name != "." && name != "..") { "Invalid encrypted filename" }
        require(!name.contains('/') && !name.contains('\\')) { "Invalid encrypted filename" }
        require(name.none(Char::isISOControl)) { "Invalid encrypted filename" }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) throw IOException("Truncated encrypted file")
            if (read == 0) continue
            offset += read
        }
        return result
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw IOException("Truncated encrypted file")
        return value
    }

    private const val BUFFER_SIZE = 64 * 1024
}

data class FileEncryptionBatchResult(
    val succeeded: List<String>,
    val failures: List<String>,
) {
    val succeededCount: Int
        get() = succeeded.size
}

@Singleton
class FileEncryptionManager @Inject constructor() {

    suspend fun encryptFiles(paths: List<String>): FileEncryptionBatchResult = withContext(Dispatchers.IO) {
        process(paths) { encryptFile(it) }
    }

    suspend fun decryptFiles(paths: List<String>): FileEncryptionBatchResult = withContext(Dispatchers.IO) {
        process(paths) { decryptFile(it) }
    }

    private suspend fun process(
        paths: List<String>,
        operation: suspend (String) -> Result<String>,
    ): FileEncryptionBatchResult {
        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (path in paths.distinct()) {
            operation(path)
                .onSuccess { succeeded += it }
                .onFailure { error -> failures += path + ": " + (error.message ?: "operation failed") }
        }
        return FileEncryptionBatchResult(succeeded, failures)
    }

    private suspend fun encryptFile(path: String): Result<String> = runCatching {
        val source = File(path).canonicalFile
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Only regular local files can be encrypted"
        }
        require(!source.name.endsWith(ENCRYPTED_SUFFIX)) { "File is already encrypted" }
        val parent = source.parentFile ?: error("File has no parent folder")
        val destination = File(parent, source.name + ENCRYPTED_SUFFIX)
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Encrypted destination already exists"
        }
        val temporary = File(parent, ".fileexplorer-encrypt-" + UUID.randomUUID() + ".tmp")
        try {
            Files.newOutputStream(temporary.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                source.inputStream().buffered().use { input ->
                    FileEncryptionEngine.encrypt(input, output, source.name, getOrCreateKey())
                }
            }
            moveIntoPlace(temporary, destination)
            destination.setLastModified(source.lastModified())
            require(Files.deleteIfExists(source.toPath())) { "Unable to remove plaintext file" }
            destination.absolutePath
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            runCatching { Files.deleteIfExists(destination.toPath()) }
            throw error
        }
    }

    private suspend fun decryptFile(path: String): Result<String> = runCatching {
        val source = File(path).canonicalFile
        require(source.name.endsWith(ENCRYPTED_SUFFIX)) { "Select a .encrypted file" }
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Only regular local files can be decrypted"
        }
        val parent = source.parentFile ?: error("File has no parent folder")
        val header = source.inputStream().buffered().use { input -> FileEncryptionEngine.readHeader(input) }
        val destination = File(parent, header.originalName)
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Decrypted destination already exists"
        }
        val temporary = File(parent, ".fileexplorer-decrypt-" + UUID.randomUUID() + ".tmp")
        try {
            Files.newOutputStream(temporary.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                source.inputStream().buffered().use { input ->
                    FileEncryptionEngine.decrypt(input, output, getOrCreateKey())
                }
            }
            moveIntoPlace(temporary, destination)
            destination.setLastModified(source.lastModified())
            require(Files.deleteIfExists(source.toPath())) { "Unable to remove encrypted file" }
            destination.absolutePath
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            runCatching { Files.deleteIfExists(destination.toPath()) }
            throw error
        }
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ENCRYPTED_SUFFIX = ".encrypted"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "file_explorer_file_encryption_v1"
    }
}
