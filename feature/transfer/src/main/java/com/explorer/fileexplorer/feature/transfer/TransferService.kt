package com.explorer.fileexplorer.feature.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.explorer.fileexplorer.core.data.FileRepository
import com.explorer.fileexplorer.core.model.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var fileRepository: FileRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    companion object {
        const val CHANNEL_ID = "file_transfer"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.explorer.fileexplorer.CANCEL_TRANSFER"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SOURCES = "sources"
        const val EXTRA_DESTINATION = "destination"

        private val _currentTask = MutableStateFlow<TransferTask?>(null)
        val currentTask: StateFlow<TransferTask?> = _currentTask.asStateFlow()

        fun startCopy(context: Context, sources: ArrayList<String>, destination: String) {
            start(context, FileOperation.COPY, sources, destination)
        }

        fun startMove(context: Context, sources: ArrayList<String>, destination: String) {
            start(context, FileOperation.MOVE, sources, destination)
        }

        fun startDelete(context: Context, paths: ArrayList<String>) {
            start(context, FileOperation.DELETE, paths, "")
        }

        private fun start(context: Context, op: FileOperation, sources: ArrayList<String>, dest: String) {
            val intent = Intent(context, TransferService::class.java).apply {
                putExtra(EXTRA_OPERATION, op.name)
                putStringArrayListExtra(EXTRA_SOURCES, sources)
                putExtra(EXTRA_DESTINATION, dest)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentJob?.cancel()
            _currentTask.value = _currentTask.value?.copy(state = TransferState.CANCELLED)
            stopSelf()
            return START_NOT_STICKY
        }

        val operation = intent?.getStringExtra(EXTRA_OPERATION)?.let { FileOperation.valueOf(it) } ?: return START_NOT_STICKY
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES) ?: return START_NOT_STICKY
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: ""

        startForeground(NOTIFICATION_ID, buildNotification("Preparing...", 0f))

        currentJob = scope.launch {
            val task = TransferTask(
                operation = operation,
                sources = emptyList(), // Simplified — full impl would resolve FileItems
                destination = destination,
                totalFiles = sources.size,
                state = TransferState.RUNNING,
            )
            _currentTask.value = task

            try {
                when (operation) {
                    FileOperation.COPY -> {
                        fileRepository.copyFiles(sources, destination, ConflictResolution.RENAME) { transferred, total, file ->
                            val progress = if (total > 0) transferred.toFloat() / total else 0f
                            _currentTask.value = task.copy(
                                transferredBytes = transferred, totalBytes = total,
                                currentFile = file, state = TransferState.RUNNING,
                            )
                            updateNotification("Copying: $file", progress)
                        }
                    }
                    FileOperation.MOVE -> {
                        fileRepository.moveFiles(sources, destination, ConflictResolution.RENAME) { transferred, total, file ->
                            val progress = if (total > 0) transferred.toFloat() / total else 0f
                            _currentTask.value = task.copy(
                                transferredBytes = transferred, totalBytes = total,
                                currentFile = file, state = TransferState.RUNNING,
                            )
                            updateNotification("Moving: $file", progress)
                        }
                    }
                    FileOperation.DELETE -> {
                        fileRepository.deleteFiles(sources) { file ->
                            _currentTask.value = task.copy(currentFile = file, state = TransferState.RUNNING)
                            updateNotification("Deleting: $file", -1f)
                        }
                    }
                    else -> {}
                }
                _currentTask.value = task.copy(state = TransferState.COMPLETED)
                updateNotification("Complete", 1f)
            } catch (e: CancellationException) {
                _currentTask.value = task.copy(state = TransferState.CANCELLED)
            } catch (e: Exception) {
                _currentTask.value = task.copy(state = TransferState.FAILED)
                updateNotification("Failed: ${e.message}", -1f)
            }

            delay(2000) // Show completion notification briefly
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        currentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "File Operations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows progress of file copy/move/delete operations" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Float): android.app.Notification {
        val cancelIntent = Intent(this, TransferService::class.java).apply { action = ACTION_CANCEL }
        val cancelPending = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("File Explorer")
            .setContentText(text)
            .setOngoing(progress in 0f..0.99f)
            .apply {
                when {
                    progress < 0 -> setProgress(0, 0, true) // Indeterminate
                    progress >= 1f -> setProgress(0, 0, false) // Complete
                    else -> setProgress(100, (progress * 100).toInt(), false)
                }
            }
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPending)
            .build()
    }

    private fun updateNotification(text: String, progress: Float) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }
}
