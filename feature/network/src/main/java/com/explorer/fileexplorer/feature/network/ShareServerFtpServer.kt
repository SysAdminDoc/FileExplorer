package com.explorer.fileexplorer.feature.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal class ShareServerFtpServer(
    private val config: ShareServerConfig,
    private val resolver: ShareServerPathResolver,
    private val serverSocket: ServerSocket,
    private val advertisedAddress: InetAddress,
) {

    fun acceptLoop(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                val socket = serverSocket.accept()
                launch {
                    socket.use { client ->
                        runCatching { handle(client) }
                    }
                }
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = CONTROL_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        var username: String? = null
        var authenticated = false
        var current = resolver.root
        var renameFrom: Path? = null
        var passiveServer: ServerSocket? = null

        fun respond(code: Int, message: String) {
            output.write("$code $message\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        fun requireAuth(): Boolean {
            if (authenticated) return true
            respond(530, "Please log in with USER and PASS")
            return false
        }

        respond(220, "File Explorer share ready")
        try {
            while (true) {
                val line = readLine(input, MAX_LINE_LENGTH) ?: break
                if (line.isBlank()) continue
                val parts = line.trim().split(' ', limit = 2)
                val command = parts[0].uppercase(Locale.US)
                val argument = parts.getOrNull(1)?.trim().orEmpty()

                when (command) {
                    "USER" -> {
                        username = argument
                        authenticated = false
                        respond(331, "Password required")
                    }
                    "PASS" -> {
                        authenticated = username == config.username && argument == config.password
                        if (authenticated) respond(230, "Logged in") else respond(530, "Authentication failed")
                    }
                    "QUIT" -> {
                        respond(221, "Goodbye")
                        break
                    }
                    "SYST" -> respond(215, "UNIX Type: L8")
                    "FEAT" -> {
                        output.write("211-Extensions\r\n UTF8\r\n EPSV\r\n PASV\r\n SIZE\r\n211 End\r\n".toByteArray(StandardCharsets.UTF_8))
                        output.flush()
                    }
                    "NOOP" -> respond(200, "OK")
                    "OPTS" -> respond(200, "UTF8 enabled")
                    "TYPE" -> respond(200, "Type set")
                    "PWD", "XPWD" -> if (requireAuth()) {
                        respond(257, "\"" + resolver.displayPath(current) + "\" is current directory")
                    }
                    "CWD" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        if (target != null && Files.isDirectory(target)) {
                            current = target
                            respond(250, "Directory changed")
                        } else {
                            respond(550, "Directory unavailable")
                        }
                    }
                    "CDUP" -> if (requireAuth()) {
                        current = current.parent?.takeIf { resolver.isWithinRoot(it) } ?: resolver.root
                        respond(250, "Directory changed")
                    }
                    "PASV" -> if (requireAuth()) {
                        passiveServer?.closeQuietly()
                        passiveServer = createPassiveServer()
                        val address = advertisedAddress.address
                        val port = passiveServer?.localPort ?: -1
                        if (address.size == 4 && port > 0) {
                            val octets = address.map { it.toInt() and 0xff }
                            respond(
                                227,
                                "Entering Passive Mode (" + octets.joinToString(",") + "," +
                                    (port / 256) + "," + (port % 256) + ")",
                            )
                        } else {
                            passiveServer?.closeQuietly()
                            passiveServer = null
                            respond(425, "Passive mode unavailable")
                        }
                    }
                    "EPSV" -> if (requireAuth()) {
                        passiveServer?.closeQuietly()
                        passiveServer = createPassiveServer()
                        val port = passiveServer?.localPort ?: -1
                        if (port > 0) respond(229, "Entering Extended Passive Mode (|||" + port + "|)")
                        else respond(425, "Passive mode unavailable")
                    }
                    "LIST", "NLST" -> if (requireAuth()) {
                        val targetArgument = argument.removeListOptions()
                        val target = if (targetArgument.isBlank()) current else resolver.resolve(current, targetArgument)
                        if (target == null || !Files.exists(target)) {
                            respond(550, "Path unavailable")
                        } else {
                            transfer(output, passiveServer, "Opening data connection") { data ->
                                val listing = ftpListing(target, namesOnly = command == "NLST")
                                data.getOutputStream().write(listing.toByteArray(StandardCharsets.UTF_8))
                            }.also { passiveServer = null }
                        }
                    }
                    "RETR" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        if (target == null || !Files.isRegularFile(target)) {
                            respond(550, "File unavailable")
                        } else {
                            transfer(output, passiveServer, "Opening data connection") { data ->
                                Files.newInputStream(target, StandardOpenOption.READ).use { fileInput ->
                                    fileInput.copyTo(data.getOutputStream())
                                }
                            }.also { passiveServer = null }
                        }
                    }
                    "STOR" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        val parent = target?.parent
                        if (target == null || target == resolver.root || Files.isDirectory(target) ||
                            parent == null || !resolver.isWithinRoot(parent) || !Files.isDirectory(parent)
                        ) {
                            respond(550, "Upload path unavailable")
                        } else {
                            val temporary = parent.resolve(".fileexplorer-upload-" + UUID.randomUUID() + ".tmp")
                            try {
                                transfer(output, passiveServer, "Ready to receive data") { data ->
                                    Files.newOutputStream(
                                        temporary,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                    ).use { fileOutput ->
                                        data.getInputStream().copyTo(fileOutput)
                                    }
                                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                                }
                            } finally {
                                runCatching { Files.deleteIfExists(temporary) }
                                passiveServer = null
                            }
                        }
                    }
                    "DELE" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        if (target == null || !Files.isRegularFile(target)) {
                            respond(550, "File unavailable")
                        } else {
                            runCatching { Files.delete(target) }
                                .onSuccess { respond(250, "File deleted") }
                                .onFailure { respond(550, "Delete failed") }
                        }
                    }
                    "MKD" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        val parent = target?.parent
                        if (target == null || Files.exists(target) || parent == null ||
                            !resolver.isWithinRoot(parent) || !Files.isDirectory(parent)
                        ) {
                            respond(550, "Folder unavailable")
                        } else {
                            runCatching { Files.createDirectory(target) }
                                .onSuccess { respond(257, "\"" + resolver.displayPath(target) + "\" created") }
                                .onFailure { respond(550, "Folder creation failed") }
                        }
                    }
                    "RMD" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        if (target == null || target == resolver.root || !Files.isDirectory(target)) {
                            respond(550, "Folder unavailable")
                        } else {
                            runCatching { Files.delete(target) }
                                .onSuccess { respond(250, "Folder removed") }
                                .onFailure { respond(550, "Folder must be empty") }
                        }
                    }
                    "RNFR" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        if (target == null || target == resolver.root || !Files.exists(target)) {
                            respond(550, "Source unavailable")
                        } else {
                            renameFrom = target
                            respond(350, "Ready for destination name")
                        }
                    }
                    "RNTO" -> if (requireAuth()) {
                        val source = renameFrom
                        val target = resolver.resolve(current, argument)
                        renameFrom = null
                        if (source == null || target == null || target == resolver.root ||
                            Files.exists(target) || !resolver.isWithinRoot(target.parent ?: resolver.root)
                        ) {
                            respond(550, "Rename destination unavailable")
                        } else {
                            runCatching { Files.move(source, target) }
                                .onSuccess { respond(250, "Rename successful") }
                                .onFailure { respond(550, "Rename failed") }
                        }
                    }
                    "SIZE" -> if (requireAuth()) {
                        val target = resolver.resolve(current, argument)
                        val size = target?.takeIf { Files.isRegularFile(it) }?.let { runCatching { Files.size(it) }.getOrNull() }
                        if (size == null) respond(550, "File unavailable") else respond(213, size.toString())
                    }
                    "ABOR" -> {
                        passiveServer?.closeQuietly()
                        passiveServer = null
                        respond(226, "Transfer aborted")
                    }
                    else -> respond(502, "Command not implemented")
                }
            }
        } finally {
            passiveServer?.closeQuietly()
        }
    }

    private fun transfer(
        output: java.io.OutputStream,
        passiveServer: ServerSocket?,
        message: String,
        block: (Socket) -> Unit,
    ): Boolean {
        if (passiveServer == null) {
            output.write("425 Use PASV or EPSV first\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            return false
        }
        output.write("150 $message\r\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
        return try {
            passiveServer.soTimeout = DATA_TIMEOUT_MS
            passiveServer.accept().use { data ->
                data.soTimeout = DATA_TIMEOUT_MS
                block(data)
            }
            output.write("226 Transfer complete\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            true
        } catch (_: Exception) {
            output.write("426 Transfer aborted\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            false
        } finally {
            passiveServer.closeQuietly()
        }
    }

    private fun createPassiveServer(): ServerSocket? = runCatching {
        ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))
    }.getOrNull()

    private fun ftpListing(path: Path, namesOnly: Boolean): String {
        val entries = if (Files.isDirectory(path)) {
            val result = mutableListOf<Path>()
            runCatching {
                Files.newDirectoryStream(path).use { stream ->
                    for (child in stream) {
                        val canonical = child.toFile().canonicalFile.toPath()
                        if (resolver.isWithinRoot(canonical)) result.add(canonical)
                    }
                }
            }
            result.sortedWith(compareBy<Path> { if (Files.isDirectory(it)) 0 else 1 }.thenBy {
                it.fileName.toString().lowercase()
            })
        } else {
            listOf(path)
        }
        return buildString {
            for (entry in entries) {
                if (namesOnly) {
                    append(entry.fileName).append("\r\n")
                } else {
                    val directory = Files.isDirectory(entry)
                    val size = runCatching { Files.size(entry) }.getOrDefault(0L)
                    val modified = runCatching { Files.getLastModifiedTime(entry).toMillis() }.getOrDefault(0L)
                    val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(modified))
                    append(if (directory) "drwxr-xr-x" else "-rw-r--r--")
                        .append(" 1 fileexplorer fileexplorer ")
                        .append(size)
                        .append(' ')
                        .append(date)
                        .append(' ')
                        .append(entry.fileName)
                        .append("\r\n")
                }
            }
        }
    }

    private fun String.removeListOptions(): String {
        val value = trim()
        if (!value.startsWith("-")) return value
        return value.substringAfter(' ', "").trim()
    }

    private fun ServerSocket.closeQuietly() {
        runCatching { close() }
    }

    private fun readLine(input: InputStream, maximumLength: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else String(
                bytes.toByteArray(),
                StandardCharsets.ISO_8859_1,
            )
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.write(next)
            if (bytes.size() > maximumLength) throw IOException("FTP line too long")
        }
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 30_000
        const val DATA_TIMEOUT_MS = 60_000
        const val MAX_LINE_LENGTH = 8 * 1024
    }
}
