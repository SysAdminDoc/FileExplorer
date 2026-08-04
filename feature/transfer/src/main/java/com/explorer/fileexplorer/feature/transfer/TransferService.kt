package com.explorer.fileexplorer.feature.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.explorer.fileexplorer.core.data.ArchiveHelper
import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.data.FileRepository
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.model.*
import com.explorer.fileexplorer.core.network.ConnectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var fileRepository: FileRepository
    @Inject lateinit var repositoryFactory: FileRepositoryFactory
    @Inject lateinit var archiveHelper: ArchiveHelper
    @Inject lateinit var connectionManager: ConnectionManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    companion object {
        const val CHANNEL_ID = "file_transfer"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.explorer.fileexplorer.CANCEL_TRANSFER"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SOURCES = "sources"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_CONFLICT = "conflict"
        const val EXTRA_REQUEST_ID = "request_id"

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

        fun startZip(
            context: Context,
            sources: ArrayList<String>,
            destination: String,
            format: String = ArchiveFormat.ZIP.extension,
        ) {
            start(context, FileOperation.COMPRESS, sources, destination, format = format)
        }

        fun startUpload(
            context: Context,
            sources: ArrayList<String>,
            destination: String,
            connectionId: Long,
        ) {
            start(context, FileOperation.UPLOAD, sources, destination, connectionId = connectionId)
        }

        private fun start(
            context: Context,
            op: FileOperation,
            sources: ArrayList<String>,
            dest: String,
            format: String? = null,
            connectionId: Long? = null,
            conflict: ConflictResolution = ConflictResolution.RENAME,
            requestId: String? = null,
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                putExtra(EXTRA_OPERATION, op.name)
                putStringArrayListExtra(EXTRA_SOURCES, sources)
                putExtra(EXTRA_DESTINATION, dest)
                putExtra(EXTRA_FORMAT, format ?: ArchiveFormat.ZIP.extension)
                putExtra(EXTRA_CONNECTION_ID, connectionId ?: 0L)
                putExtra(EXTRA_CONFLICT, conflict.name)
                requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
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

        val operation = intent?.getStringExtra(EXTRA_OPERATION)?.let {
            runCatching { FileOperation.valueOf(it) }.getOrNull()
        } ?: return START_NOT_STICKY
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES) ?: return START_NOT_STICKY
        if (sources.isEmpty()) return START_NOT_STICKY
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: ""
        val format = runCatching {
            AutomationContract.parseArchiveFormat(intent.getStringExtra(EXTRA_FORMAT))
        }.getOrElse { return START_NOT_STICKY }
        val conflict = runCatching {
            intent.getStringExtra(EXTRA_CONFLICT)
                ?.let { ConflictResolution.valueOf(it) }
                ?: ConflictResolution.RENAME
        }.getOrDefault(ConflictResolution.RENAME)
        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, 0L).takeIf { it > 0L }
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)

        startForeground(NOTIFICATION_ID, buildNotification("Preparing...", 0f))

        currentJob = scope.launch {
            val repository = when (operation) {
                FileOperation.DELETE -> repositoryFactory.getRepository(sources.first())
                FileOperation.COPY, FileOperation.MOVE -> repositoryFactory.getRepository(destination)
                else -> fileRepository
            }
            val totalBytes = runCatching {
                when (operation) {
                    FileOperation.COPY, FileOperation.MOVE -> repository.calculateSize(sources)
                    FileOperation.COMPRESS, FileOperation.UPLOAD -> fileRepository.calculateSize(sources)
                    else -> 0L
                }
            }.getOrDefault(0L)
            val task = TransferTask(
                operation = operation,
                sources = emptyList(), // Simplified — full impl would resolve FileItems
                destination = destination,
                totalBytes = totalBytes,
                totalFiles = sources.size,
                state = TransferState.RUNNING,
            )
            _currentTask.value = task

            var status = AutomationContract.STATUS_COMPLETED
            var errorMessage: String? = null
            try {
                when (operation) {
                    FileOperation.COPY -> {
                        repository.copyFiles(sources, destination, conflict) { transferred, total, file ->
                            val progress = if (total > 0) transferred.toFloat() / total else 0f
                            _currentTask.value = _currentTask.value?.copy(
                                transferredBytes = transferred, totalBytes = total,
                                currentFile = file, state = TransferState.RUNNING,
                            )
                            updateNotification("Copying: $file", progress)
                        }.getOrThrow()
                    }
                    FileOperation.MOVE -> {
                        repository.moveFiles(sources, destination, conflict) { transferred, total, file ->
                            val progress = if (total > 0) transferred.toFloat() / total else 0f
                            _currentTask.value = _currentTask.value?.copy(
                                transferredBytes = transferred, totalBytes = total,
                                currentFile = file, state = TransferState.RUNNING,
                            )
                            updateNotification("Moving: $file", progress)
                        }.getOrThrow()
                    }
                    FileOperation.DELETE -> {
                        repository.deleteFiles(sources) { file ->
                            _currentTask.value = _currentTask.value?.copy(currentFile = file, state = TransferState.RUNNING)
                            updateNotification("Deleting: $file", -1f)
                        }.getOrThrow()
                    }
                    FileOperation.COMPRESS -> {
                        archiveHelper.createArchive(
                            outputPath = destination,
                            sourcePaths = sources,
                            format = format,
                        ) { _, _, file ->
                            _currentTask.value = _currentTask.value?.copy(
                                currentFile = file,
                                state = TransferState.RUNNING,
                            )
                            updateNotification("Creating archive: $file", -1f)
                        }.getOrThrow()
                    }
                    FileOperation.UPLOAD -> {
                        uploadFiles(
                            sources = sources,
                            destination = destination,
                            connectionId = connectionId,
                            totalBytes = totalBytes,
                        )
                    }
                    FileOperation.EXTRACT -> throw UnsupportedOperationException(
                        "Extract is not available through the automation contract",
                    )
                }
                _currentTask.value = _currentTask.value?.copy(state = TransferState.COMPLETED)
                updateNotification("Complete", 1f)
            } catch (e: CancellationException) {
                status = AutomationContract.STATUS_CANCELLED
                errorMessage = e.message
                _currentTask.value = _currentTask.value?.copy(state = TransferState.CANCELLED)
            } catch (e: Exception) {
                status = AutomationContract.STATUS_FAILED
                errorMessage = e.message
                _currentTask.value = _currentTask.value?.copy(state = TransferState.FAILED)
                updateNotification("Failed: ${e.message}", -1f)
            }

            sendAutomationResult(requestId, operation, status, errorMessage)
            delay(2000) // Show completion notification briefly
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        _currentTask.value = _currentTask.value?.copy(state = TransferState.FAILED)
        updateNotification("Operation timed out by system", -1f)
        currentJob?.cancel()
        stopSelf(startId)
    }

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

    private suspend fun uploadFiles(
        sources: List<String>,
        destination: String,
        connectionId: Long?,
        totalBytes: Long,
    ) {
        val id = requireNotNull(connectionId) { "Upload requires a saved connection" }
        val existing = connectionManager.getActiveRepo(id)?.takeIf { it.isConnected }
        val connectedByAction = existing == null
        val repository = existing ?: connectionManager.connectById(id).getOrThrow()
        var transferredBefore = 0L

        try {
            sources.forEach { source ->
                require(java.io.File(source).isFile) {
                    "Upload source is not a local file: $source"
                }
                val remotePath = if (sources.size == 1) {
                    destination
                } else {
                    destination.trimEnd('/') + "/" + source.substringAfterLast('/').substringAfterLast('\\')
                }
                repository.upload(source, remotePath) { transferred, _ ->
                    val absolute = transferredBefore + transferred
                    val progress = if (totalBytes > 0L) {
                        (absolute.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else {
                        -1f
                    }
                    _currentTask.value = _currentTask.value?.copy(
                        transferredBytes = absolute,
                        totalBytes = totalBytes,
                        currentFile = source,
                        state = TransferState.RUNNING,
                    )
                    updateNotification("Uploading: $source", progress)
                }.getOrThrow()
                transferredBefore += java.io.File(source).length()
            }
        } finally {
            if (connectedByAction) connectionManager.disconnect(id)
        }
    }

    private fun sendAutomationResult(
        requestId: String?,
        operation: FileOperation,
        status: String,
        error: String?,
    ) {
        if (requestId.isNullOrBlank()) return
        sendBroadcast(Intent(AutomationContract.ACTION_RESULT).apply {
            putExtra(AutomationContract.EXTRA_REQUEST_ID, requestId)
            putExtra(
                AutomationContract.EXTRA_OPERATION,
                if (operation == FileOperation.COMPRESS) "ZIP" else operation.name,
            )
            putExtra(AutomationContract.EXTRA_STATUS, status)
            error?.let { putExtra(AutomationContract.EXTRA_ERROR, it) }
        })
    }
}
