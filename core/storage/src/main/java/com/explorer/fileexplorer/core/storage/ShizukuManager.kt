package com.explorer.fileexplorer.core.storage

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

enum class ShizukuAvailability {
    UNSUPPORTED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
}

data class ShizukuStatus(
    val availability: ShizukuAvailability,
    val uid: Int = -1,
) {
    val isReady: Boolean get() = availability == ShizukuAvailability.READY
}

data class ShizukuCommandResult(
    val exitCode: Int,
    val output: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val _status = kotlinx.coroutines.flow.MutableStateFlow(
        ShizukuStatus(ShizukuAvailability.NOT_RUNNING),
    )
    val status: kotlinx.coroutines.flow.StateFlow<ShizukuStatus> = _status

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context, ShizukuShellService::class.java),
    )
        .daemon(false)
        .version(USER_SERVICE_VERSION)
        .tag(USER_SERVICE_TAG)
        .processNameSuffix("shizuku-files")

    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var remoteService: IShizukuShellService? = null
    private var binding: CompletableDeferred<IShizukuShellService>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = service?.let { IShizukuShellService.Stub.asInterface(it) }
            synchronized(lock) {
                remoteService = remote
                binding?.let { deferred ->
                    if (remote == null) deferred.completeExceptionally(IllegalStateException("Shizuku service unavailable"))
                    else deferred.complete(remote)
                    binding = null
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { remoteService = null }
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(lock) { remoteService = null }
        refresh()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST_CODE) refresh()
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        val next = try {
            when {
                Shizuku.isPreV11() -> ShizukuStatus(ShizukuAvailability.UNSUPPORTED)
                !Shizuku.pingBinder() -> ShizukuStatus(ShizukuAvailability.NOT_RUNNING)
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuStatus(ShizukuAvailability.PERMISSION_REQUIRED)
                else -> ShizukuStatus(ShizukuAvailability.READY, Shizuku.getUid())
            }
        } catch (_: Throwable) {
            ShizukuStatus(ShizukuAvailability.NOT_RUNNING)
        }
        _status.value = next
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (_: Throwable) {
            refresh()
        }
    }

    /**
     * Runs [command] in the Shizuku UserService. [timeoutMs] bounds the command itself; 0 or less
     * means no limit. A bounded call also stops waiting on the app side, so a binder call that never
     * returns surfaces as an error instead of leaving the caller loading forever.
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): ShizukuCommandResult = withContext(Dispatchers.IO) {
        if (!status.value.isReady) {
            return@withContext ShizukuCommandResult(1, "Shizuku permission is not ready")
        }
        try {
            val remote = service()
            // Binder calls are not cancellable, so run the call outside this coroutine and only
            // bound how long we wait for it.
            val call = callScope.async { remote.execute(command, timeoutMs) }
            val response = if (timeoutMs > 0) {
                withTimeout(timeoutMs + CLIENT_TIMEOUT_GRACE_MS) { call.await() }
            } else {
                call.await()
            }
            val separator = response.indexOf('\n')
            if (separator <= 0) {
                ShizukuCommandResult(1, response)
            } else {
                ShizukuCommandResult(
                    response.substring(0, separator).toIntOrNull() ?: 1,
                    response.substring(separator + 1),
                )
            }
        } catch (error: TimeoutCancellationException) {
            ShizukuCommandResult(ShizukuShellService.TIMEOUT_EXIT_CODE, "Shizuku did not respond in time")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is RemoteException) {
                synchronized(lock) { remoteService = null }
            }
            refresh()
            ShizukuCommandResult(1, error.message ?: "Shizuku command failed")
        }
    }

    private suspend fun service(): IShizukuShellService {
        var shouldBind = false
        val deferred: CompletableDeferred<IShizukuShellService>
        synchronized(lock) {
            remoteService?.let { return it }
            deferred = binding ?: CompletableDeferred<IShizukuShellService>().also {
                binding = it
                shouldBind = true
            }
        }
        if (shouldBind) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (binding === deferred) binding = null
                }
                deferred.completeExceptionally(error)
            }
        }
        try {
            return withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            // Drop the pending bind so the next call binds again instead of awaiting a
            // connection that is never coming.
            synchronized(lock) {
                if (binding === deferred) binding = null
            }
            throw error
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 7401
        private const val USER_SERVICE_VERSION = 2
        private const val USER_SERVICE_TAG = "file-explorer-shizuku-files"
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val CLIENT_TIMEOUT_GRACE_MS = 5_000L
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        const val NO_TIMEOUT = 0L
    }
}
