package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.FileItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BatchRenameOptions(
    val template: String,
    val regex: String = "",
    val counterStart: Int = 1,
    val counterPadding: Int = 2,
    val datePattern: String = "yyyy-MM-dd",
)

data class BatchRenamePreviewItem(
    val item: FileItem,
    val newName: String,
    val targetPath: String,
    val error: String? = null,
) {
    val isChanged: Boolean get() = item.name != newName
}

data class BatchRenamePreview(
    val items: List<BatchRenamePreviewItem>,
) {
    val errors: List<String> get() = items.mapNotNull { it.error }.distinct()
    val isValid: Boolean get() = items.isNotEmpty() && errors.isEmpty() && items.any { it.isChanged }
    val changedItems: List<BatchRenamePreviewItem> get() = items.filter { it.isChanged && it.error == null }
}

object BatchRenameEngine {

    private val tokenPattern = Regex("\\{([A-Za-z][A-Za-z0-9]*|\\d+)}")

    fun preview(items: List<FileItem>, options: BatchRenameOptions): BatchRenamePreview {
        if (items.isEmpty()) return BatchRenamePreview(emptyList())

        val regex = if (options.regex.isBlank()) {
            null
        } else {
            try {
                Regex(options.regex)
            } catch (_: IllegalArgumentException) {
                return BatchRenamePreview(items.map { item ->
                    previewError(item, "Invalid regular expression")
                })
            }
        }
        val dateFormatter = try {
            DateTimeFormatter.ofPattern(options.datePattern)
        } catch (_: IllegalArgumentException) {
            return BatchRenamePreview(items.map { item ->
                previewError(item, "Invalid date pattern")
            })
        }

        val previews = items.mapIndexed { index, item ->
            val match = regex?.find(item.name)
            if (regex != null && match == null) {
                previewError(item, "Pattern does not match ${item.name}")
            } else {
                val result = expandTemplate(item, options, index, match, dateFormatter)
                result.fold(
                    onSuccess = { name ->
                        val validation = validateName(name)
                        if (validation == null) {
                            BatchRenamePreviewItem(item, name, siblingPath(item.path, name))
                        } else {
                            previewError(item, validation)
                        }
                    },
                    onFailure = { error -> previewError(item, error.message ?: "Unable to build name") },
                )
            }
        }.toMutableList()

        val duplicateTargets = previews
            .filter { it.error == null }
            .groupBy { it.targetPath.lowercase(Locale.ROOT) }
            .values
            .filter { group -> group.size > 1 }
            .flatten()
            .map { it.item.path }
            .toSet()
        if (duplicateTargets.isNotEmpty()) {
            previews.replaceAll { item ->
                if (item.item.path in duplicateTargets && item.error == null) {
                    item.copy(error = "Duplicate target name")
                } else {
                    item
                }
            }
        }
        return BatchRenamePreview(previews)
    }

    fun siblingPath(path: String, name: String): String {
        val separator = path.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (separator < 0) name else path.substring(0, separator + 1) + name
    }

    private fun expandTemplate(
        item: FileItem,
        options: BatchRenameOptions,
        index: Int,
        match: MatchResult?,
        dateFormatter: DateTimeFormatter,
    ): Result<String> = runCatching {
        tokenPattern.replace(options.template) { token ->
            val key = token.groupValues[1].lowercase(Locale.ROOT)
            when (key) {
                "name" -> item.baseName()
                "ext", "extension" -> item.extensionWithDot()
                    .let { extension -> if (key == "ext") extension else extension.removePrefix(".") }
                "parent" -> parentName(item.path)
                "counter" -> (options.counterStart + index).toString().padStart(options.counterPadding.coerceAtLeast(0), '0')
                "date" -> formatDate(item, dateFormatter)
                else -> if (key.startsWith("group") && key.removePrefix("group").toIntOrNull() != null) {
                    val group = key.removePrefix("group").toInt()
                    match?.groups?.get(group)?.value
                        ?: throw IllegalArgumentException("Missing regex group $group")
                } else if (key.toIntOrNull() != null) {
                    val group = key.toInt()
                    match?.groups?.get(group)?.value
                        ?: throw IllegalArgumentException("Missing regex group $group")
                } else {
                    throw IllegalArgumentException("Unknown token {$key}")
                }
            }
        }
    }

    private fun formatDate(item: FileItem, formatter: DateTimeFormatter): String {
        val timestamp = item.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()
        return formatter.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
    }

    private fun validateName(name: String): String? = when {
        name.isBlank() -> "Generated name is blank"
        name == "." || name == ".." -> "Generated name is reserved"
        name.any { it == '/' || it == '\\' } -> "Generated name must not contain a path separator"
        name.any { it.code < 0x20 } -> "Generated name contains a control character"
        else -> null
    }

    private fun previewError(item: FileItem, error: String): BatchRenamePreviewItem =
        BatchRenamePreviewItem(item, item.name, item.path, error)

    private fun FileItem.baseName(): String {
        val extension = extensionWithDot()
        return if (extension.isNotEmpty()) name.removeSuffix(extension) else name
    }

    private fun FileItem.extensionWithDot(): String {
        if (isDirectory) return ""
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.lastIndex) name.substring(dot) else ""
    }

    private fun parentName(path: String): String {
        val normalized = path.trimEnd('/', '\\')
        val separator = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
        if (separator < 0) return ""
        val parent = normalized.substring(0, separator).trimEnd('/', '\\')
        val parentSeparator = parent.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (parentSeparator < 0) parent else parent.substring(parentSeparator + 1)
    }
}
