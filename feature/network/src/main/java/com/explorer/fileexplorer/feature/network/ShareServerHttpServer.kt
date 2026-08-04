package com.explorer.fileexplorer.feature.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal class ShareServerHttpServer(
    private val config: ShareServerConfig,
    private val resolver: ShareServerPathResolver,
    private val serverSocket: ServerSocket,
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
        socket.soTimeout = REQUEST_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = readLine(input, MAX_LINE_LENGTH) ?: return
        val requestParts = requestLine.split(' ', limit = 3)
        if (requestParts.size != 3 || !requestParts[2].startsWith("HTTP/")) {
            sendText(output, "400 Bad Request", "Malformed HTTP request")
            return
        }

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input, MAX_LINE_LENGTH) ?: run {
                sendText(output, "400 Bad Request", "Incomplete HTTP headers")
                return
            }
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) {
                sendText(output, "400 Bad Request", "Malformed HTTP header")
                return
            }
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }

        if (!isAuthorized(headers["authorization"])) {
            sendResponse(
                output = output,
                status = "401 Unauthorized",
                body = "Authentication required".toByteArray(StandardCharsets.UTF_8),
                contentType = "text/plain; charset=utf-8",
                extraHeaders = listOf("WWW-Authenticate: Basic realm=\"File Explorer\""),
            )
            return
        }

        val decodedPath = decodePath(requestParts[1].substringBefore('?'))
        if (decodedPath == null) {
            sendText(output, "400 Bad Request", "Invalid URL encoding")
            return
        }
        val path = resolver.resolveFromRoot(decodedPath)
        if (path == null) {
            sendText(output, "403 Forbidden", "Path is outside the shared folder")
            return
        }

        when (requestParts[0].uppercase()) {
            "GET", "HEAD" -> serveGet(output, path, resolver.displayPath(path), requestParts[0] == "HEAD")
            "PUT" -> upload(input, output, path, headers["content-length"])
            "DELETE" -> delete(output, path)
            "MKCOL" -> makeDirectory(output, path)
            "OPTIONS" -> sendResponse(
                output = output,
                status = "204 No Content",
                body = ByteArray(0),
                extraHeaders = listOf("Allow: GET, HEAD, PUT, DELETE, MKCOL, OPTIONS"),
            )
            else -> sendResponse(
                output = output,
                status = "405 Method Not Allowed",
                body = "Method not supported".toByteArray(StandardCharsets.UTF_8),
                contentType = "text/plain; charset=utf-8",
                extraHeaders = listOf("Allow: GET, HEAD, PUT, DELETE, MKCOL, OPTIONS"),
            )
        }
    }

    private fun serveGet(output: OutputStream, path: Path, requestPath: String, headOnly: Boolean) {
        if (!Files.exists(path)) {
            sendText(output, "404 Not Found", "File not found")
            return
        }
        if (Files.isDirectory(path)) {
            val body = directoryListing(path, requestPath).toByteArray(StandardCharsets.UTF_8)
            sendResponse(
                output = output,
                status = "200 OK",
                body = if (headOnly) ByteArray(0) else body,
                contentLength = body.size.toLong(),
                contentType = "text/html; charset=utf-8",
            )
            return
        }
        if (!Files.isRegularFile(path)) {
            sendText(output, "409 Conflict", "Unsupported file type")
            return
        }

        val length = runCatching { Files.size(path) }.getOrElse {
            sendText(output, "500 Internal Server Error", "Unable to read file")
            return
        }
        val contentType = URLConnection.guessContentTypeFromName(path.fileName.toString())
            ?: "application/octet-stream"
        writeHeaders(output, "200 OK", length, contentType)
        if (!headOnly) {
            runCatching {
                Files.newInputStream(path, StandardOpenOption.READ).use { input ->
                    input.copyTo(output)
                }
                output.flush()
            }
        }
    }

    private fun upload(input: InputStream, output: OutputStream, path: Path, lengthHeader: String?) {
        if (path == resolver.root || Files.isDirectory(path)) {
            sendText(output, "405 Method Not Allowed", "A file path is required")
            return
        }
        val length = lengthHeader?.toLongOrNull()
        if (length == null || length < 0) {
            sendText(output, "411 Length Required", "Content-Length is required")
            return
        }
        val parent = path.parent
        if (parent == null || !resolver.isWithinRoot(parent) || !Files.isDirectory(parent)) {
            sendText(output, "409 Conflict", "Parent folder does not exist")
            return
        }

        val existed = Files.exists(path)
        val temporary = parent.resolve(".fileexplorer-upload-\${UUID.randomUUID()}.tmp")
        try {
            var copied = 0L
            Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { fileOutput ->
                copied = copyExactly(input, fileOutput, length)
            }
            if (copied != length) {
                throw IOException("Upload ended before Content-Length")
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            sendResponse(
                output = output,
                status = if (existed) "204 No Content" else "201 Created",
                body = ByteArray(0),
            )
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            sendText(output, "500 Internal Server Error", "Upload failed")
        }
    }

    private fun delete(output: OutputStream, path: Path) {
        if (path == resolver.root) {
            sendText(output, "403 Forbidden", "The shared folder cannot be deleted")
            return
        }
        if (!Files.exists(path)) {
            sendText(output, "404 Not Found", "File not found")
            return
        }
        runCatching {
            deleteRecursively(path)
        }.onSuccess {
            sendResponse(output, "204 No Content", ByteArray(0))
        }.onFailure {
            sendText(output, "500 Internal Server Error", "Delete failed")
        }
    }

    private fun makeDirectory(output: OutputStream, path: Path) {
        if (path == resolver.root || Files.exists(path)) {
            sendText(output, "405 Method Not Allowed", "Folder already exists")
            return
        }
        val parent = path.parent
        if (parent == null || !resolver.isWithinRoot(parent) || !Files.isDirectory(parent)) {
            sendText(output, "409 Conflict", "Parent folder does not exist")
            return
        }
        runCatching { Files.createDirectory(path) }
            .onSuccess { sendResponse(output, "201 Created", ByteArray(0)) }
            .onFailure { sendText(output, "500 Internal Server Error", "Folder creation failed") }
    }

    private fun directoryListing(path: Path, requestPath: String): String {
        val children = mutableListOf<Path>()
        runCatching {
            Files.newDirectoryStream(path).use { stream: DirectoryStream<Path> ->
                for (child in stream) {
                    val canonical = child.toFile().canonicalFile.toPath()
                    if (resolver.isWithinRoot(canonical)) children.add(canonical)
                }
            }
        }
        children.sortWith(compareBy<Path> { if (Files.isDirectory(it)) 0 else 1 }.thenBy {
            it.fileName.toString().lowercase()
        })

        val html = StringBuilder()
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>File Explorer</title>")
        html.append("<style>body{font-family:sans-serif;margin:2rem}a{display:block;padding:.45rem 0}")
        html.append("small{color:#666}</style></head><body><h1>File Explorer</h1>")
        html.append("<p><small>").append(htmlEscape(requestPath)).append("</small></p>")
        if (path != resolver.root) {
            val parent = resolver.displayPath(path.parent ?: resolver.root)
            html.append("<a href=\"").append(encodePath(parent)).append("\">..</a>")
        }
        for (child in children) {
            val name = child.fileName.toString()
            val childPath = resolver.displayPath(child) + if (Files.isDirectory(child)) "/" else ""
            html.append("<a href=\"").append(encodePath(childPath)).append("\">")
                .append(if (Files.isDirectory(child)) "📁 " else "📄 ")
                .append(htmlEscape(name))
                .append("</a>")
        }
        html.append("</body></html>")
        return html.toString()
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
            Files.newDirectoryStream(path).use { stream ->
                for (child in stream) {
                    val canonical = child.toFile().canonicalFile.toPath()
                    if (resolver.isWithinRoot(canonical)) deleteRecursively(canonical)
                }
            }
        }
        Files.deleteIfExists(path)
    }

    private fun isAuthorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64Decoder.decode(header.substringAfter(' ')), StandardCharsets.UTF_8)
        }.getOrNull() ?: return false
        val separator = decoded.indexOf(':')
        if (separator < 1) return false
        return decoded.substring(0, separator) == config.username &&
            decoded.substring(separator + 1) == config.password
    }

    private fun decodePath(rawPath: String): String? {
        if (rawPath.isEmpty()) return "/"
        if (!rawPath.startsWith('/')) return null
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < rawPath.length) {
            val character = rawPath[index]
            if (character == '%') {
                if (index + 2 >= rawPath.length) return null
                val high = rawPath[index + 1].digitToIntOrNull(16) ?: return null
                val low = rawPath[index + 2].digitToIntOrNull(16) ?: return null
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                val encoded = character.toString().toByteArray(StandardCharsets.UTF_8)
                bytes.write(encoded)
                index++
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
            .takeUnless { it.any(Char::isISOControl) }
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        if (segment.isEmpty()) "" else URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
    }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        var copied = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            copied += read
            remaining -= read
        }
        return copied
    }

    private fun sendText(output: OutputStream, status: String, text: String) {
        sendResponse(
            output = output,
            status = status,
            body = text.toByteArray(StandardCharsets.UTF_8),
            contentType = "text/plain; charset=utf-8",
        )
    }

    private fun sendResponse(
        output: OutputStream,
        status: String,
        body: ByteArray,
        contentLength: Long = body.size.toLong(),
        contentType: String? = null,
        extraHeaders: List<String> = emptyList(),
    ) {
        writeHeaders(output, status, contentLength, contentType, extraHeaders)
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun writeHeaders(
        output: OutputStream,
        status: String,
        contentLength: Long,
        contentType: String? = null,
        extraHeaders: List<String> = emptyList(),
    ) {
        val headers = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        headers.append("Content-Length: ").append(contentLength).append("\r\n")
        if (contentType != null) headers.append("Content-Type: ").append(contentType).append("\r\n")
        headers.append("Connection: close\r\n")
        extraHeaders.forEach { headers.append(it).append("\r\n") }
        headers.append("\r\n")
        output.write(headers.toString().toByteArray(StandardCharsets.US_ASCII))
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
            if (bytes.size() > maximumLength) throw IOException("HTTP line too long")
        }
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private object Base64Decoder {
        fun decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_LINE_LENGTH = 16 * 1024
        const val REQUEST_TIMEOUT_MS = 30_000
    }
}
