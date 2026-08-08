package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

object SecureDelete {
    private val random = SecureRandom()

    suspend fun secureDelete(path: String, passes: Int = 3): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(path.isNotBlank()) { "Secure-delete path must not be blank" }
            require(passes > 0) { "Secure-delete passes must be positive" }
            val target = Paths.get(path).toAbsolutePath().normalize()
            require(target.nameCount > 0) { "Secure delete refuses a filesystem root" }
            secureDeletePath(target, passes)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun secureDeletePath(path: Path, passes: Int) {
        coroutineContext.ensureActive()
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isSymbolicLink) {
            throw IllegalArgumentException("Secure delete does not follow symbolic links: $path")
        }
        if (attributes.isDirectory) {
            Files.newDirectoryStream(path).use { children ->
                for (child in children) secureDeletePath(child, passes)
            }
        } else if (attributes.isRegularFile) {
            overwriteFile(path, attributes.size(), passes)
        } else {
            throw IllegalArgumentException("Secure delete does not support special files: $path")
        }
        if (!Files.deleteIfExists(path)) {
            throw IllegalStateException("Unable to remove securely deleted path: $path")
        }
    }

    private suspend fun overwriteFile(path: Path, length: Long, passes: Int) {
        if (length <= 0L) return
        RandomAccessFile(path.toFile(), "rw").use { raf ->
            val buffer = ByteArray(65536)
            repeat(passes + 1) { pass ->
                coroutineContext.ensureActive()
                raf.seek(0)
                var remaining = length
                while (remaining > 0L) {
                    coroutineContext.ensureActive()
                    val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
                    when (pass % 3) {
                        0 -> random.nextBytes(buffer)
                        1 -> buffer.fill(0x00.toByte())
                        else -> buffer.fill(0xFF.toByte())
                    }
                    raf.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
                raf.fd.sync()
            }
        }
    }
}
