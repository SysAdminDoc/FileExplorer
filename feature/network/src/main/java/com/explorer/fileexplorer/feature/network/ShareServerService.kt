package com.explorer.fileexplorer.feature.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShareServerService : Service() {

    @Inject
    lateinit var controller: ShareServerController

    @Inject
    lateinit var settingsStore: ShareServerSettingsStore

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("Starting share server", ongoing = true))
        val result = controller.start(settingsStore.load())
        result.onFailure { error ->
            updateNotification("Share server failed: " + (error.message ?: "unable to start"), ongoing = false)
            stopSelf(startId)
        }
        result.onSuccess {
            updateNotification("Share server running; HTTP and FTP are plaintext", ongoing = true)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (controller.status.value.isRunning) controller.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, ongoing))
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
        val stopIntent = Intent(this, ShareServerService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = openIntent?.let {
            PendingIntent.getActivity(this, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("File Explorer share server")
            .setContentText(text)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (openPendingIntent != null) setContentIntent(openPendingIntent)
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Share server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when File Explorer is sharing files over the local network"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.explorer.fileexplorer.START_SHARE_SERVER"
        const val ACTION_STOP = "com.explorer.fileexplorer.STOP_SHARE_SERVER"
        const val CHANNEL_ID = "share_server"
        const val NOTIFICATION_ID = 1002
        private const val REQUEST_STOP = 1002
        private const val REQUEST_OPEN = 1003

        fun start(context: Context) {
            val intent = Intent(context, ShareServerService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShareServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
