package com.explorer.fileexplorer.feature.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class IntegrityFingerprint(
    val sha256: String,
    val size: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
)

/** Deterministic SHA-256 fingerprints for files and recursively watched directories. */
object IntegrityHasher {
    suspend fun fingerprint(path: String): Result<IntegrityFingerprint> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            require(file.exists()) { "Path does not exist: $path" }
            if (file.isDirectory) fingerprintDirectory(file) else fingerprintFile(file)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun fingerprintFile(file: File): Result<IntegrityFingerprint> {
        val digest = MessageDigest.getInstance("SHA-256")
        updateFileContent(digest, file)
        return Result.success(
            IntegrityFingerprint(
                sha256 = digest.digest().toHexString(),
                size = file.length(),
                modifiedAt = file.lastModified(),
                isDirectory = false,
            ),
        )
    }

    private fun fingerprintDirectory(directory: File): Result<IntegrityFingerprint> {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalSize = 0L
        val children = directory.walkTopDown()
            .filter { it != directory }
            .toList()
            .sortedBy { relativePath(directory, it) }

        for (child in children) {
            val relative = relativePath(directory, child)
            digest.update((if (child.isDirectory) 'D'.code else 'F'.code).toByte())
            digest.update(relative.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            if (child.isFile) {
                val size = child.length()
                updateLong(digest, size)
                updateFileContent(digest, child)
                totalSize += size
            }
        }

        return Result.success(
            IntegrityFingerprint(
                sha256 = digest.digest().toHexString(),
                size = totalSize,
                modifiedAt = directory.lastModified(),
                isDirectory = true,
            ),
        )
    }

    private fun updateFileContent(digest: MessageDigest, file: File) {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            digest.update((value ushr shift).toByte())
        }
    }

    private fun relativePath(root: File, child: File): String =
        root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/')

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val BUFFER_SIZE = 64 * 1024
}
