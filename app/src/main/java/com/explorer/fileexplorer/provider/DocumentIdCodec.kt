package com.explorer.fileexplorer.provider

import java.nio.charset.StandardCharsets
import java.util.Base64

internal object DocumentIdCodec {

    fun encode(path: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(StandardCharsets.UTF_8))

    fun decode(documentId: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(documentId), StandardCharsets.UTF_8)
    }.getOrNull()
}
