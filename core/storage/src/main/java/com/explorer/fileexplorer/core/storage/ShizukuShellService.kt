package com.explorer.fileexplorer.core.storage

import java.io.InputStream

/**
 * Small Shizuku UserService used by the file repository. It intentionally exposes one
 * bounded command surface to the app process; all path validation and command construction
 * stays in [ShizukuFileRepository].
 */
class ShizukuShellService : IShizukuShellService.Stub() {
    override fun execute(command: String): String {
        require(command.length <= MAX_COMMAND_LENGTH) { "Shizuku command is too long" }
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        return try {
            val output = readBounded(process.inputStream, MAX_OUTPUT_BYTES)
            if (output.truncated) process.destroy()
            val exitCode = process.waitFor()
            "$exitCode\n${output.text}"
        } finally {
            process.inputStream.close()
            process.destroy()
        }
    }

    private data class BoundedOutput(
        val text: String,
        val truncated: Boolean,
    )

    private fun readBounded(input: InputStream, maxBytes: Int): BoundedOutput {
        val output = StringBuilder()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read < 0) break
            if (read == 0) continue
            output.append(String(buffer, 0, read, Charsets.UTF_8))
            total += read
        }
        return BoundedOutput(output.toString(), total >= maxBytes)
    }

    companion object {
        private const val MAX_COMMAND_LENGTH = 16 * 1024
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    }
}
