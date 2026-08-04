package com.explorer.fileexplorer.core.data

import android.content.Context
import com.explorer.fileexplorer.core.storage.RootHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class EncryptedVolumeFormat(
    val label: String,
    val executable: String,
    val configName: String,
) {
    GOCRYPTFS("gocryptfs", "gocryptfs", "gocryptfs.conf"),
    ENCFS("EncFS", "encfs", ".encfs6.xml"),
}

data class EncryptedVolumeRequest(
    val format: EncryptedVolumeFormat,
    val cipherPath: String,
    val mountPath: String,
    val readOnly: Boolean = false,
)

data class EncryptedVolumeMount(
    val format: EncryptedVolumeFormat,
    val cipherPath: String,
    val mountPath: String,
    val readOnly: Boolean,
)

/** Validation shared by the root command builder and the encrypted-volume UI. */
object EncryptedVolumePathPolicy {
    fun normalize(path: String): Result<String> {
        if (path.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return Result.failure(IllegalArgumentException("Path contains an invalid control character"))
        }
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        if (normalized == "/") return Result.failure(IllegalArgumentException("The root path cannot be used"))
        if (!normalized.startsWith('/')) {
            return Result.failure(IllegalArgumentException("Path must be absolute"))
        }
        return Result.success(normalized)
    }
}

internal object EncryptedVolumeCommandBuilder {
    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun mountCommand(
        request: EncryptedVolumeRequest,
        passFile: String,
    ): String {
        val readOnly = if (request.readOnly) "-ro " else ""
        return when (request.format) {
            EncryptedVolumeFormat.GOCRYPTFS ->
                "gocryptfs -q ${readOnly}-allow_other -passfile ${shellQuote(passFile)} " +
                    "${shellQuote(request.cipherPath)} ${shellQuote(request.mountPath)}"

            EncryptedVolumeFormat.ENCFS -> {
                val externalPasswordCommand = "cat ${shellQuote(passFile)}"
                "encfs --extpass=${shellQuote(externalPasswordCommand)} " +
                    "-o allow_other${if (request.readOnly) ",ro" else ""} " +
                    "${shellQuote(request.cipherPath)} ${shellQuote(request.mountPath)}"
            }
        }
    }

    fun unmountCommand(mountPath: String): String =
        "fusermount -u ${shellQuote(mountPath)} 2>/dev/null || " +
            "umount ${shellQuote(mountPath)} 2>/dev/null"
}

@Singleton
class EncryptedVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootHelper: RootHelper,
    private val rootFileRepository: RootFileRepository,
) {
    suspend fun detectFormats(): List<EncryptedVolumeFormat> = withContext(Dispatchers.IO) {
        if (!rootHelper.isRooted) return@withContext emptyList()
        EncryptedVolumeFormat.entries.filter { format ->
            rootHelper.exec("command -v ${format.executable} >/dev/null 2>&1").isSuccess
        }
    }

    suspend fun listMounted(): List<EncryptedVolumeMount> = withContext(Dispatchers.IO) {
        if (!rootHelper.isRooted) return@withContext emptyList()
        rootFileRepository.getMountPoints().mapNotNull { mount ->
            val format = when {
                mount.fsType.contains("gocryptfs", ignoreCase = true) ||
                    mount.device.contains("gocryptfs", ignoreCase = true) -> EncryptedVolumeFormat.GOCRYPTFS

                mount.fsType.contains("encfs", ignoreCase = true) ||
                    mount.device.contains("encfs", ignoreCase = true) -> EncryptedVolumeFormat.ENCFS

                else -> null
            } ?: return@mapNotNull null
            EncryptedVolumeMount(
                format = format,
                cipherPath = mount.device,
                mountPath = mount.mountPoint,
                readOnly = mount.isReadOnly,
            )
        }
    }

    suspend fun mount(
        request: EncryptedVolumeRequest,
        passphrase: CharArray,
    ): Result<EncryptedVolumeMount> = withContext(Dispatchers.IO) {
        val normalizedRequest = normalizeRequest(request).getOrElse { error ->
            passphrase.fill('\u0000')
            return@withContext Result.failure(error)
        }
        if (passphrase.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Passphrase cannot be empty"))
        }
        if (passphrase.any { it == '\n' || it == '\r' }) {
            passphrase.fill('\u0000')
            return@withContext Result.failure(IllegalArgumentException("Passphrase cannot contain a newline"))
        }
        if (!rootHelper.isRooted) {
            passphrase.fill('\u0000')
            return@withContext Result.failure(IllegalStateException("Root access is not available"))
        }
        if (normalizedRequest.format !in detectFormats()) {
            passphrase.fill('\u0000')
            return@withContext Result.failure(
                IllegalStateException("${normalizedRequest.format.label} is not installed in the root environment"),
            )
        }

        val existingMounts = rootFileRepository.getMountPoints()
        if (existingMounts.any { it.mountPoint == normalizedRequest.mountPath }) {
            passphrase.fill('\u0000')
            return@withContext Result.failure(IllegalStateException("Mount path is already mounted"))
        }

        val cipherPath = EncryptedVolumeCommandBuilder.shellQuote(normalizedRequest.cipherPath)
        val mountPath = EncryptedVolumeCommandBuilder.shellQuote(normalizedRequest.mountPath)
        val configPath = EncryptedVolumeCommandBuilder.shellQuote(
            "${normalizedRequest.cipherPath}/${normalizedRequest.format.configName}",
        )
        val directoryCheck = rootHelper.exec("test -d $cipherPath && test -f $configPath")
        if (!directoryCheck.isSuccess) {
            passphrase.fill('\u0000')
            return@withContext Result.failure(
                IllegalArgumentException("Cipher directory or ${normalizedRequest.format.configName} was not found"),
            )
        }

        val mountPreparation = rootHelper.exec(
            "mkdir -p $mountPath && test -z \"\$(ls -A $mountPath 2>/dev/null)\"",
        )
        if (!mountPreparation.isSuccess) {
            passphrase.fill('\u0000')
            return@withContext Result.failure(
                IllegalArgumentException("Mount path must be an empty directory"),
            )
        }

        val passFile = File(context.cacheDir, ".encrypted-volume-pass-${UUID.randomUUID()}")
        try {
            writePassphrase(passFile, passphrase)
            val command = EncryptedVolumeCommandBuilder.mountCommand(normalizedRequest, passFile.absolutePath)
            val result = rootHelper.exec(command)
            if (!result.isSuccess) {
                return@withContext Result.failure(commandFailure("Mount command failed", result.err))
            }

            val mounted = listMounted().firstOrNull { it.mountPath == normalizedRequest.mountPath }
            if (mounted == null) {
                return@withContext Result.failure(
                    IllegalStateException("Mount command completed but the volume was not reported as mounted"),
                )
            }
            Result.success(mounted)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            passphrase.fill('\u0000')
            erasePassphraseFile(passFile)
        }
    }

    suspend fun unmount(mountPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedPath = EncryptedVolumePathPolicy.normalize(mountPath).getOrElse { error ->
            return@withContext Result.failure(error)
        }
        if (!rootHelper.isRooted) {
            return@withContext Result.failure(IllegalStateException("Root access is not available"))
        }
        if (listMounted().none { it.mountPath == normalizedPath }) {
            return@withContext Result.failure(IllegalArgumentException("No encrypted volume is mounted at this path"))
        }
        val result = rootHelper.exec(EncryptedVolumeCommandBuilder.unmountCommand(normalizedPath))
        if (!result.isSuccess) {
            return@withContext Result.failure(commandFailure("Unmount command failed", result.err))
        }
        if (listMounted().any { it.mountPath == normalizedPath }) {
            Result.failure(IllegalStateException("Volume is still mounted"))
        } else {
            Result.success(Unit)
        }
    }

    private fun normalizeRequest(request: EncryptedVolumeRequest): Result<EncryptedVolumeRequest> {
        val cipherPath = EncryptedVolumePathPolicy.normalize(request.cipherPath)
            .getOrElse { return Result.failure(it) }
        val mountPath = EncryptedVolumePathPolicy.normalize(request.mountPath)
            .getOrElse { return Result.failure(it) }
        return Result.success(request.copy(cipherPath = cipherPath, mountPath = mountPath))
    }

    private fun writePassphrase(passFile: File, passphrase: CharArray) {
        val bytes = passphrase.concatToString().toByteArray(StandardCharsets.UTF_8)
        try {
            FileOutputStream(passFile).use { output ->
                output.write(bytes)
                output.write('\n'.code)
                output.fd.sync()
            }
            passFile.setReadable(false, false)
            passFile.setReadable(true, true)
            passFile.setWritable(false, false)
            passFile.setWritable(true, true)
            passFile.setExecutable(false, false)
        } finally {
            bytes.fill(0)
        }
    }

    private fun erasePassphraseFile(passFile: File) {
        runCatching {
            if (passFile.exists()) {
                val length = passFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                FileOutputStream(passFile, false).use { output ->
                    output.write(ByteArray(length))
                    output.fd.sync()
                }
                passFile.delete()
            }
        }
    }

    private fun commandFailure(prefix: String, errors: List<String>): IllegalStateException {
        val detail = errors.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        return IllegalStateException(if (detail == null) prefix else "$prefix: $detail")
    }
}
