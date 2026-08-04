package com.explorer.fileexplorer.feature.transfer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Converts external automation broadcasts into validated foreground transfer requests. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = AutomationContract.parse(
            action = intent.action,
            sourcePaths = readSources(intent),
            destination = intent.getStringExtra(AutomationContract.EXTRA_DESTINATION),
            format = intent.getStringExtra(AutomationContract.EXTRA_FORMAT),
            connectionId = readLongExtra(intent, AutomationContract.EXTRA_CONNECTION_ID),
            conflict = intent.getStringExtra(AutomationContract.EXTRA_CONFLICT),
        )

        request.onFailure { error ->
            setResultCode(Activity.RESULT_CANCELED)
            setResultData(error.message ?: "Invalid automation request")
        }.onSuccess { parsed ->
            val serviceIntent = Intent(context, TransferService::class.java).apply {
                putExtra(
                    TransferService.EXTRA_OPERATION,
                    when (parsed.operation) {
                        AutomationContract.Operation.COPY -> "COPY"
                        AutomationContract.Operation.MOVE -> "MOVE"
                        AutomationContract.Operation.ZIP -> "COMPRESS"
                        AutomationContract.Operation.UPLOAD -> "UPLOAD"
                    },
                )
                putStringArrayListExtra(
                    TransferService.EXTRA_SOURCES,
                    ArrayList(parsed.sourcePaths),
                )
                putExtra(TransferService.EXTRA_DESTINATION, parsed.destination)
                putExtra(TransferService.EXTRA_FORMAT, parsed.archiveFormat.extension)
                putExtra(TransferService.EXTRA_CONNECTION_ID, parsed.connectionId ?: 0L)
                putExtra(
                    TransferService.EXTRA_CONFLICT,
                    parsed.conflictResolution.name,
                )
                intent.getStringExtra(AutomationContract.EXTRA_REQUEST_ID)?.let {
                    putExtra(TransferService.EXTRA_REQUEST_ID, it)
                }
            }

            runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
                .onSuccess {
                    setResultCode(Activity.RESULT_OK)
                    setResultData("accepted")
                }
                .onFailure { error ->
                    setResultCode(Activity.RESULT_CANCELED)
                    setResultData(error.message ?: "Unable to start transfer service")
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun readSources(intent: Intent): List<String> {
        intent.getStringArrayListExtra(AutomationContract.EXTRA_SOURCES)?.let { return it }
        intent.getStringArrayExtra(AutomationContract.EXTRA_SOURCES)?.let { return it.toList() }
        intent.getStringExtra(AutomationContract.EXTRA_SOURCE)?.let { return listOf(it) }
        return emptyList()
    }

    private fun readLongExtra(intent: Intent, key: String): Long? {
        val longValue = intent.getLongExtra(key, Long.MIN_VALUE)
        if (longValue != Long.MIN_VALUE) return longValue
        val intValue = intent.getIntExtra(key, Int.MIN_VALUE)
        if (intValue != Int.MIN_VALUE) return intValue.toLong()
        return intent.getStringExtra(key)?.toLongOrNull()
    }
}
