package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.explorer.fileexplorer.core.model.RepositoryError
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticEntry(
    val timestamp: Long,
    val provider: String,
    val operation: String,
    val error: String,
    val detail: String = "",
)

@Singleton
class DiagnosticLog @Inject constructor() {
    private val maxEntries = 200
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: Flow<List<DiagnosticEntry>> = _entries.asStateFlow()

    fun log(provider: String, operation: String, error: Throwable, detail: String = "") {
        val redacted = redactSecrets(error.message ?: error.javaClass.simpleName)
        val entry = DiagnosticEntry(
            timestamp = System.currentTimeMillis(),
            provider = provider,
            operation = operation,
            error = redacted,
            detail = detail,
        )
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    fun log(provider: String, operation: String, message: String) {
        val entry = DiagnosticEntry(
            timestamp = System.currentTimeMillis(),
            provider = provider,
            operation = operation,
            error = redactSecrets(message),
        )
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    /** Records structured provider context without allowing raw credentials into exports. */
    fun log(error: RepositoryError, detail: String = "") {
        val status = error.statusCode?.let { " status=$it" }.orEmpty()
        val entry = DiagnosticEntry(
            timestamp = System.currentTimeMillis(),
            provider = error.provider,
            operation = error.operation.name,
            error = redactSecrets(
                "kind=${error.kind.name} retryable=${error.retryable}$status; ${error.message}",
            ),
            detail = redactSecrets(detail),
        )
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    suspend fun exportToString(): String = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        buildString {
            appendLine("FileExplorer Diagnostic Log")
            appendLine("Exported: ${fmt.format(Date())}")
            appendLine("Entries: ${_entries.value.size}")
            appendLine("---")
            for (e in _entries.value) {
                appendLine("[${fmt.format(Date(e.timestamp))}] ${e.provider} / ${e.operation}")
                appendLine("  Error: ${e.error}")
                if (e.detail.isNotBlank()) appendLine("  Detail: ${e.detail}")
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun redactSecrets(msg: String): String {
        return msg
            .replace(Regex("password[=:]\\S+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("Bearer \\S+"), "Bearer ***")
            .replace(Regex("token[=:]\\S+", RegexOption.IGNORE_CASE), "token=***")
    }
}
