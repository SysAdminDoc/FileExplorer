package com.explorer.fileexplorer.feature.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareServerController @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val settingsStore = ShareServerSettingsStore(context)
    private val _status = MutableStateFlow(ShareServerStatus(config = settingsStore.load()))
    val status: StateFlow<ShareServerStatus> = _status.asStateFlow()

    private var runtime: Runtime? = null

    @Synchronized
    fun start(config: ShareServerConfig): Result<Unit> {
        val normalized = runCatching {
            validate(config)
            config.normalized()
        }.getOrElse { error ->
            _status.value = ShareServerStatus(
                state = ShareServerState.FAILED,
                config = config,
                error = error.message ?: "Invalid server configuration",
            )
            return Result.failure(error)
        }

        stopInternal(updateStatus = false)
        val root = runCatching { Paths.get(normalized.rootPath) }.getOrElse { error ->
            return failStart(normalized, error)
        }
        if (!Files.isDirectory(root)) {
            return failStart(normalized, IllegalArgumentException("Shared folder does not exist"))
        }

        val runtimeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var httpSocket: ServerSocket? = null
        var ftpSocket: ServerSocket? = null
        try {
            _status.value = ShareServerStatus(ShareServerState.STARTING, normalized)
            if (normalized.httpEnabled) httpSocket = openServerSocket(normalized.httpPort)
            if (normalized.ftpEnabled) ftpSocket = openServerSocket(normalized.ftpPort)

            val resolver = ShareServerPathResolver(root.toString())
            val advertisedAddress = localIpv4Addresses().firstOrNull() ?: InetAddress.getLoopbackAddress()
            val httpServer = httpSocket?.let { ShareServerHttpServer(normalized, resolver, it) }
            val ftpServer = ftpSocket?.let {
                ShareServerFtpServer(normalized, resolver, it, advertisedAddress)
            }
            val newRuntime = Runtime(
                scope = runtimeScope,
                httpSocket = httpSocket,
                ftpSocket = ftpSocket,
                httpJob = httpServer?.acceptLoop(runtimeScope),
                ftpJob = ftpServer?.acceptLoop(runtimeScope),
            )
            runtime = newRuntime
            val addresses = shareAddresses(normalized, localIpv4Addresses())
            _status.value = ShareServerStatus(
                state = ShareServerState.RUNNING,
                config = normalized,
                addresses = addresses,
            )
            return Result.success(Unit)
        } catch (error: Exception) {
            httpSocket?.closeQuietly()
            ftpSocket?.closeQuietly()
            runtimeScope.cancel()
            return failStart(normalized, error)
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(updateStatus = true)
    }

    private fun validate(config: ShareServerConfig) {
        require(config.rootPath.isNotBlank()) { "Shared folder is required" }
        require(config.httpEnabled || config.ftpEnabled) { "Enable HTTP or FTP" }
        if (config.httpEnabled) require(config.httpPort in ShareServerConfig.MIN_PORT..ShareServerConfig.MAX_PORT) {
            "HTTP port must be between " + ShareServerConfig.MIN_PORT + " and " + ShareServerConfig.MAX_PORT
        }
        if (config.ftpEnabled) require(config.ftpPort in ShareServerConfig.MIN_PORT..ShareServerConfig.MAX_PORT) {
            "FTP port must be between " + ShareServerConfig.MIN_PORT + " and " + ShareServerConfig.MAX_PORT
        }
        if (config.httpEnabled && config.ftpEnabled) require(config.httpPort != config.ftpPort) {
            "HTTP and FTP ports must be different"
        }
        require(config.username.isNotBlank()) { "Username is required" }
        require(!config.username.contains(':')) { "Username cannot contain ':'" }
        require(config.password.isNotBlank()) { "Password is required" }
    }

    private fun failStart(config: ShareServerConfig, error: Throwable): Result<Unit> {
        _status.value = ShareServerStatus(
            state = ShareServerState.FAILED,
            config = config,
            error = error.message ?: "Unable to start server",
        )
        return Result.failure(error)
    }

    private fun openServerSocket(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 32)
    }

    private fun shareAddresses(config: ShareServerConfig, addresses: List<InetAddress>): List<String> {
        val visibleAddresses = addresses.ifEmpty { listOf(InetAddress.getLoopbackAddress()) }
        return buildList {
            for (address in visibleAddresses) {
                val host = address.hostAddress ?: continue
                if (config.httpEnabled) add("http://" + host + ":" + config.httpPort)
                if (config.ftpEnabled) add("ftp://" + host + ":" + config.ftpPort)
            }
        }
    }

    private fun localIpv4Addresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp }.getOrDefault(false)) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    result += address
                }
            }
        }
        return result.distinctBy { it.hostAddress }
    }

    private fun stopInternal(updateStatus: Boolean) {
        val previous = runtime
        runtime = null
        previous?.scope?.cancel()
        previous?.httpSocket?.closeQuietly()
        previous?.ftpSocket?.closeQuietly()
        if (updateStatus) {
            _status.value = ShareServerStatus(
                state = ShareServerState.STOPPED,
                config = _status.value.config ?: settingsStore.load(),
            )
        }
    }

    private fun ServerSocket.closeQuietly() {
        runCatching { close() }
    }

    private data class Runtime(
        val scope: CoroutineScope,
        val httpSocket: ServerSocket?,
        val ftpSocket: ServerSocket?,
        val httpJob: kotlinx.coroutines.Job?,
        val ftpJob: kotlinx.coroutines.Job?,
    )
}
