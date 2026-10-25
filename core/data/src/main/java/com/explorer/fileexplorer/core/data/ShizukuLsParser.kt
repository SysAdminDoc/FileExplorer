package com.explorer.fileexplorer.core.data

/** One entry of `ls -l` output from the Shizuku shell. */
internal data class ShizukuLsEntry(
    val permissions: String,
    val owner: String,
    val group: String,
    val size: Long,
    val name: String,
    val symlinkTarget: String?,
) {
    val isDirectory: Boolean get() = permissions[0] == 'd'
    val isSymlink: Boolean get() = permissions[0] == 'l'
}

/**
 * Parses `ls -l` lines. Android's toybox prints `perms links owner group size YYYY-MM-DD HH:MM name`,
 * while GNU and busybox print a three-field date such as `Jan  1 12:00`. The name is everything after
 * the date, so names that contain spaces stay whole.
 */
internal object ShizukuLsParser {
    private val whitespace = "\\s+".toRegex()
    private val isoDate = "\\d{4}-\\d{2}-\\d{2}".toRegex()

    /**
     * Parses one line. With [nameIsPath], the listed name is a path (as `ls -ld <path>` prints it) and
     * only its last segment is kept.
     */
    fun parse(line: String, nameIsPath: Boolean = false): ShizukuLsEntry? {
        val trimmed = line.trim()
        val head = trimmed.split(whitespace, limit = 8)
        if (head.size < 8 || head[0].length < 10) return null
        val rawName = if (isoDate.matches(head[5])) {
            head[7]
        } else {
            trimmed.split(whitespace, limit = 9).getOrNull(8) ?: return null
        }
        val isLink = head[0][0] == 'l'
        val linkParts = if (isLink) rawName.split(" -> ", limit = 2) else listOf(rawName)
        var name = linkParts[0].trimEnd('/')
        if (nameIsPath) name = name.substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") return null
        return ShizukuLsEntry(
            permissions = head[0],
            owner = head[2],
            group = head[3],
            size = head[4].toLongOrNull() ?: 0L,
            name = name,
            symlinkTarget = linkParts.getOrNull(1),
        )
    }
}
