package com.explorer.fileexplorer.core.cloud

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

class StreamingFileBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var transferred = 0L
        val buf = ByteArray(65536)
        file.inputStream().use { input ->
            var len: Int
            while (input.read(buf).also { len = it } != -1) {
                sink.write(buf, 0, len)
                transferred += len
                onProgress(transferred, total)
            }
        }
    }
}
