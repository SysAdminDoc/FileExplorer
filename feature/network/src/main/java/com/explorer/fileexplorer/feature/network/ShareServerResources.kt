package com.explorer.fileexplorer.feature.network

import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

internal object ShareServerLimits {
    const val MAX_CONNECTIONS = 8
    const val MAX_REQUESTS_PER_MINUTE = 120
    const val MAX_UPLOAD_BYTES: Long = 256L * 1024L * 1024L
    const val MAX_TEMP_STORAGE_BYTES: Long = 512L * 1024L * 1024L
    const val MAX_DIRECTORY_ENTRIES = 2_000
    const val MAX_DIRECTORY_LISTING_BYTES = 1_048_576
    const val REQUEST_TIMEOUT_MS = 30_000
    const val DATA_TIMEOUT_MS = 60_000
    const val MAX_HTTP_HEADER_COUNT = 64
    const val MAX_HTTP_HEADER_BYTES = 64 * 1024
    const val TEMPORARY_FILE_PREFIX = ".fileexplorer-upload-"
}

internal class ShareServerRuntimeResources(
    maxConnections: Int = ShareServerLimits.MAX_CONNECTIONS,
    private val maxRequestsPerMinute: Int = ShareServerLimits.MAX_REQUESTS_PER_MINUTE,
    private val maxTemporaryBytes: Long = ShareServerLimits.MAX_TEMP_STORAGE_BYTES,
) {

    private val connections = Semaphore(maxConnections, true)
    private val temporaryBytes = AtomicLong(0L)
    private val requestWindows = mutableMapOf<String, RequestWindow>()

    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxRequestsPerMinute > 0) { "maxRequestsPerMinute must be positive" }
        require(maxTemporaryBytes > 0) { "maxTemporaryBytes must be positive" }
    }

    fun tryAcquireConnection(): Boolean = connections.tryAcquire()

    fun releaseConnection() {
        connections.release()
    }

    @Synchronized
    fun allowRequest(clientAddress: String): Boolean {
        val now = System.currentTimeMillis()
        val key = clientAddress.ifBlank { "unknown" }
        val previous = requestWindows[key]
        val window = if (previous == null || now - previous.startedAt >= WINDOW_MS) {
            RequestWindow(startedAt = now, count = 1).also { requestWindows[key] = it }
        } else if (previous.count >= maxRequestsPerMinute) {
            return false
        } else {
            previous.count++
            previous
        }

        if (requestWindows.size > MAX_TRACKED_CLIENTS) {
            val oldest = requestWindows.entries
                .sortedBy { it.value.startedAt }
                .take(requestWindows.size - MAX_TRACKED_CLIENTS)
            oldest.forEach { requestWindows.remove(it.key) }
        }
        return window.count <= maxRequestsPerMinute
    }

    fun tryReserveTemporary(bytes: Long): Boolean {
        require(bytes >= 0) { "bytes must not be negative" }
        if (bytes == 0L) return true
        if (bytes > maxTemporaryBytes) return false
        while (true) {
            val current = temporaryBytes.get()
            if (current > maxTemporaryBytes - bytes) return false
            if (temporaryBytes.compareAndSet(current, current + bytes)) return true
        }
    }

    fun releaseTemporary(bytes: Long) {
        require(bytes >= 0) { "bytes must not be negative" }
        if (bytes == 0L) return
        temporaryBytes.updateAndGet { current -> (current - bytes).coerceAtLeast(0L) }
    }

    private data class RequestWindow(
        val startedAt: Long,
        var count: Int,
    )

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_TRACKED_CLIENTS = 4_096
    }
}

internal class ShareServerLimitExceededException(message: String) : IOException(message)
