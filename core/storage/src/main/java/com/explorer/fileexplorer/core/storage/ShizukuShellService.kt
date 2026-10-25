package com.explorer.fileexplorer.core.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Small Shizuku UserService used by the file repository. It intentionally exposes one
 * bounded command surface to the app process; all path validation and command construction
 * stays in [ShizukuFileRepository].
 */
class ShizukuShellService : IShizukuShellService.Stub() {
    override fun execute(command: String, timeoutMs: Long): String {
        require(command.length <= MAX_COMMAND_LENGTH) { "Shizuku command is too long" }
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = BoundedOutput()
        // Read on a separate thread so a command that never exits cannot block the binder
        // call forever; the deadline below is enforced against the process, not the pipe.
        val reader = Thread({ output.readFrom(process.inputStream, process) }, "shizuku-shell-output")
        reader.isDaemon = true
        reader.start()
        return try {
            val finished = if (timeoutMs > 0) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }
            if (!finished) {
                process.destroyForcibly()
                reader.join(READER_DRAIN_MS)
                return "$TIMEOUT_EXIT_CODE\nShizuku command timed out after ${timeoutMs / 1000}s"
            }
            reader.join(READER_DRAIN_MS)
            "${process.exitValue()}\n${output.text()}"
        } finally {
            process.destroy()
            runCatching { process.inputStream.close() }
        }
    }

    private class BoundedOutput {
        private val bytes = ByteArrayOutputStream()

        fun readFrom(input: InputStream, process: Process) {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(bytes) {
                        val remaining = MAX_OUTPUT_BYTES - bytes.size()
                        bytes.write(buffer, 0, minOf(read, remaining))
                        if (read >= remaining) {
                            process.destroy()
                            return
                        }
                    }
                }
            } catch (_: Exception) {
                // The stream closes when the process is destroyed after a timeout.
            }
        }

        // Decode once so multi-byte UTF-8 sequences split across reads stay intact.
        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
    }

    companion object {
        private const val MAX_COMMAND_LENGTH = 16 * 1024
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val READER_DRAIN_MS = 2_000L
        const val TIMEOUT_EXIT_CODE = 124
    }
}
