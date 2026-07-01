package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

object SecureDelete {
    private val random = SecureRandom()

    suspend fun secureDelete(path: String, passes: Int = 3): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext Result.failure(Exception("File not found"))
            if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    secureDelete(child.absolutePath, passes)
                }
                file.delete()
                return@withContext Result.success(Unit)
            }

            val length = file.length()
            if (length > 0) {
                RandomAccessFile(file, "rw").use { raf ->
                    val buf = ByteArray(65536)
                    repeat(passes) { pass ->
                        raf.seek(0)
                        var remaining = length
                        while (remaining > 0) {
                            val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                            when (pass % 3) {
                                0 -> random.nextBytes(buf)
                                1 -> buf.fill(0x00.toByte())
                                2 -> buf.fill(0xFF.toByte())
                            }
                            raf.write(buf, 0, toWrite)
                            remaining -= toWrite
                        }
                        raf.fd.sync()
                    }
                    raf.seek(0)
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                        random.nextBytes(buf)
                        raf.write(buf, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                }
            }
            file.delete()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
}
