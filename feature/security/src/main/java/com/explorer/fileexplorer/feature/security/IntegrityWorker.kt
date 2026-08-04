package com.explorer.fileexplorer.feature.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IntegrityWorkerEntryPoint {
    fun integrityRepository(): IntegrityRepository
}

class IntegrityWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            IntegrityWorkerEntryPoint::class.java,
        ).integrityRepository()
        return repository.scanNow().fold(
            onSuccess = { summary ->
                IntegrityNotifier.notifyIfNeeded(applicationContext, summary)
                Result.success()
            },
            onFailure = { Result.retry() },
        )
    }
}

private object IntegrityNotifier {
    private const val CHANNEL_ID = "file_integrity_alerts"
    private const val NOTIFICATION_ID = 2201

    fun notifyIfNeeded(context: Context, summary: IntegrityScanSummary) {
        if (summary.newlyAlerted == 0) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "File integrity alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Alerts when watched files change or disappear" },
            )
        }
        val details = buildString {
            if (summary.changed > 0) append("${summary.changed} changed")
            if (summary.missing > 0) {
                if (isNotEmpty()) append(", ")
                append("${summary.missing} missing")
            }
            if (summary.errors > 0) {
                if (isNotEmpty()) append(", ")
                append("${summary.errors} unreadable")
            }
        }
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("File integrity warning")
                .setContentText(details.ifEmpty { "Watched paths need attention" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setAutoCancel(true)
                .build(),
        )
    }
}
