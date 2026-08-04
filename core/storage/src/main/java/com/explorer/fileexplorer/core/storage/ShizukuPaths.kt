package com.explorer.fileexplorer.core.storage

/** Paths that may be exposed through the optional Shizuku backend. */
object ShizukuPaths {
    const val ANDROID_DATA_ROOT = "/storage/emulated/0/Android/data"
    const val ANDROID_OBB_ROOT = "/storage/emulated/0/Android/obb"

    private val allowedRoots = setOf(ANDROID_DATA_ROOT, ANDROID_OBB_ROOT)

    fun isAllowed(path: String): Boolean {
        val normalized = normalize(path) ?: return false
        return allowedRoots.any { root -> normalized == root || normalized.startsWith("$root/") }
    }

    fun isProtectedRoot(path: String): Boolean {
        val normalized = normalize(path) ?: return false
        return normalized in allowedRoots
    }

    fun child(parent: String, name: String): String? {
        if (!isAllowed(parent) || !isSafeName(name)) return null
        return "${parent.trimEnd('/')}/$name"
    }

    fun shellQuote(value: String): String {
        require(value.indexOf('\u0000') < 0) { "NUL is not a valid shell argument" }
        return "'${value.replace("'", "'\\''")}'"
    }

    private fun normalize(path: String): String? {
        if (!path.startsWith('/') || path.indexOf('\u0000') >= 0) return null
        val parts = path.split('/')
        val normalized = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> {
                    if (part.any(Char::isISOControl)) return null
                    normalized.addLast(part)
                }
            }
        }
        return "/" + normalized.joinToString("/")
    }

    private fun isSafeName(name: String): Boolean {
        return name.isNotEmpty() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && !name.any(Char::isISOControl)
    }
}
