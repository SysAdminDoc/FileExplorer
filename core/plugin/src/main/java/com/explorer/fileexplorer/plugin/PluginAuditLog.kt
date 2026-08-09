package com.explorer.fileexplorer.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class PluginAuditOutcome {
    APPROVED,
    REVOKED,
    CALL_SUCCEEDED,
    CALL_REJECTED,
    CALL_FAILED,
    CALL_TIMED_OUT,
    BINDER_DIED,
}

data class PluginAuditEntry(
    val timestamp: Long,
    val pluginId: String,
    val operation: String,
    val outcome: PluginAuditOutcome,
    val detail: String = "",
)

/** In-memory, path-free audit trail for plugin trust and IPC decisions. */
@Singleton
class PluginAuditLog @Inject constructor() {
    private val _entries = MutableStateFlow<List<PluginAuditEntry>>(emptyList())
    val entries: Flow<List<PluginAuditEntry>> = _entries.asStateFlow()

    fun record(
        pluginId: String,
        operation: String,
        outcome: PluginAuditOutcome,
        detail: String = "",
    ) {
        _entries.value = (
            _entries.value + PluginAuditEntry(
                timestamp = System.currentTimeMillis(),
                pluginId = pluginId,
                operation = operation,
                outcome = outcome,
                detail = detail.take(MAX_DETAIL_LENGTH),
            )
        ).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 200
        const val MAX_DETAIL_LENGTH = 128
    }
}
