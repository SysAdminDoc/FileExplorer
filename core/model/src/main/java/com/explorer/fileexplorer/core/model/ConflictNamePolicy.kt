package com.explorer.fileexplorer.core.model

import java.security.MessageDigest

/** Stable names let a retried transfer target the same keep-both artifact. */
object ConflictNamePolicy {
    fun suffix(operationKey: String, sourcePath: String, destinationPath: String): String {
        val input = "$operationKey\u0000$sourcePath\u0000$destinationPath"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }

    fun fileName(originalName: String, suffix: String): String {
        val safeSuffix = suffix.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24)
            .ifBlank { "copy" }
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val extension = if (dot > 0) originalName.substring(dot) else ""
        return "$base (copy-$safeSuffix)$extension"
    }

    fun pathWithName(path: String, suffix: String): String {
        val separator = path.lastIndexOfAny(charArrayOf('/', '\\'))
        val name = if (separator >= 0) path.substring(separator + 1) else path
        val parent = if (separator >= 0) path.substring(0, separator + 1) else ""
        return parent + fileName(name, suffix)
    }
}
