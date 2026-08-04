package com.explorer.fileexplorer.feature.browser

import android.content.Context
import com.explorer.fileexplorer.core.model.FileItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.CastSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(DEFAULT_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Google Cast's built-in Default Media Receiver application. */
        const val DEFAULT_RECEIVER_APPLICATION_ID = "CC1AD845"
    }
}

object CastMediaPolicy {
    private val mediaExtensions = setOf(
        "aac", "flac", "m4a", "mp3", "oga", "ogg", "wav", "webm",
        "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ts", "3gp",
        "avif", "bmp", "gif", "jpeg", "jpg", "png", "webp",
    )

    fun isCastable(item: FileItem): Boolean = !item.isDirectory &&
        (item.mimeType.startsWith("audio/") || item.mimeType.startsWith("image/") ||
            item.mimeType.startsWith("video/") || item.extension.lowercase() in mediaExtensions)
}

@Singleton
class CastMediaSender @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val server = CastMediaServer(context)
    private var listener: SessionManagerListener<CastSession>? = null

    suspend fun cast(item: FileItem): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (!CastMediaPolicy.isCastable(item)) {
            return@withContext Result.failure(IllegalArgumentException("Select a photo, video, or audio file"))
        }
        val castContext = runCatching { CastContext.getSharedInstance(context) }
            .getOrElse { error ->
                return@withContext Result.failure(
                    IllegalStateException("Google Cast is unavailable", error),
                )
            }
        registerListener(castContext)
        val session = castContext.sessionManager.currentCastSession
            ?: return@withContext Result.failure(IllegalStateException("Connect to a Cast device first"))
        val remoteMediaClient = session.remoteMediaClient
            ?: return@withContext Result.failure(IllegalStateException("The Cast device cannot play media"))
        val mediaUrl = server.start(item).getOrElse { error ->
            return@withContext Result.failure(error)
        }
        val metadataType = when {
            item.mimeType.startsWith("audio/") -> MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            item.mimeType.startsWith("image/") -> MediaMetadata.MEDIA_TYPE_PHOTO
            else -> MediaMetadata.MEDIA_TYPE_MOVIE
        }
        val metadata = MediaMetadata(metadataType).apply {
            putString(MediaMetadata.KEY_TITLE, item.name)
        }
        val mediaInfo = MediaInfo.Builder(mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(item.mimeType.ifBlank { "application/octet-stream" })
            .setMetadata(metadata)
            .setStreamDuration(item.size.takeIf { it >= 0L } ?: 0L)
            .build()
        remoteMediaClient.load(
            com.google.android.gms.cast.MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .build(),
        )
        Result.success(Unit)
    }

    fun stop() {
        server.stop()
    }

    private fun registerListener(castContext: CastContext) {
        if (listener != null) return
        val newListener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStarted(session: CastSession, sessionId: String) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = server.stop()
            override fun onSessionEnded(session: CastSession, error: Int) = server.stop()
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = server.stop()
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        listener = newListener
        castContext.sessionManager.addSessionManagerListener(newListener, CastSession::class.java)
    }
}

private class CastMediaServer(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: kotlinx.coroutines.Job? = null

    fun start(item: FileItem): Result<String> {
        stop()
        if (item.size < 0L) return Result.failure(IOException("Media size is unavailable"))
        val host = localHostAddress() ?: return Result.failure(IOException("No local network address"))
        val socket = runCatching { ServerSocket(0, 8, InetAddress.getByName("0.0.0.0")) }
            .getOrElse { return Result.failure(it) }
        val path = "/media/${UUID.randomUUID().toString().replace("-", "")}"
        serverSocket = socket
        serverJob = scope.launch {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { serve(client, path, item) }
            }
        }
        return Result.success("http://$host:${socket.localPort}$path")
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun serve(client: Socket, expectedPath: String, item: FileItem) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                line.substringBefore(':', "").trim().lowercase().takeIf { it.isNotEmpty() }?.let { key ->
                    headers[key] = line.substringAfter(':', "").trim()
                }
            }
            val requestParts = requestLine.split(' ', limit = 3)
            val method = requestParts.firstOrNull() ?: return
            val path = requestParts.getOrNull(1) ?: return
            if (path != expectedPath) {
                writeResponse(socket, "404 Not Found", "text/plain", 0L, null)
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeResponse(socket, "405 Method Not Allowed", "text/plain", 0L, null)
                return
            }
            val total = item.size
            val range = headers["range"]?.let { parseRange(it, total) }
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1L)
            if (range == null && headers["range"] != null) {
                writeResponse(socket, "416 Range Not Satisfiable", "text/plain", 0L, null)
                return
            }
            val contentLength = if (total == 0L) 0L else (end - start + 1L).coerceAtLeast(0L)
            val input = if (method == "GET" && contentLength > 0L) openInput(item) else null
            if (method == "GET" && contentLength > 0L && input == null) {
                writeResponse(socket, "404 Not Found", "text/plain", 0L, null)
                return
            }
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val contentType = safeMimeType(item.mimeType)
            val response = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(contentLength).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = socket.getOutputStream()
            output.write(response.toByteArray(StandardCharsets.US_ASCII))
            if (method == "HEAD" || contentLength == 0L) {
                output.flush()
                return
            }
            input?.use { source ->
                skipFully(source, start)
                copyBytes(source, output, contentLength)
            }
            output.flush()
        }
    }

    private fun writeResponse(socket: Socket, status: String, contentType: String, length: Long, body: ByteArray?) {
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: $length\r\nConnection: close\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII),
        )
        body?.let(output::write)
        output.flush()
    }

    private fun openInput(item: FileItem): InputStream? = runCatching {
        item.uri?.let(context.contentResolver::openInputStream)
            ?: FileInputStream(File(item.path))
    }.getOrNull()

    private fun parseRange(value: String, total: Long): Pair<Long, Long>? {
        if (!value.startsWith("bytes=") || total <= 0L) return null
        val range = value.removePrefix("bytes=").substringBefore(',')
        val startText = range.substringBefore('-').trim()
        val endText = range.substringAfter('-', "").trim()
        val start = startText.toLongOrNull() ?: return null
        if (start !in 0 until total) return null
        val end = endText.toLongOrNull()?.coerceAtMost(total - 1L) ?: (total - 1L)
        return if (end >= start) start to end else null
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped else if (input.read() == -1) break else remaining--
        }
    }

    private fun copyBytes(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(32 * 1024)
        var remaining = limit
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun safeMimeType(mimeType: String): String =
        mimeType.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+\\-/]+")) } ?: "application/octet-stream"

    private fun localHostAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
