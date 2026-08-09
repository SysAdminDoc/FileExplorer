package com.explorer.fileexplorer.feature.network

import com.explorer.fileexplorer.core.data.DiagnosticLog
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
    private val settingsStore: ShareServerSettingsStore,
    private val diagnosticLog: DiagnosticLog,
) {

    private val _status = MutableStateFlow(ShareServerStatus(config = settingsStore.load()))
    val status: StateFlow<ShareServerStatus> = _status.asStateFlow()

    private var runtime: Runtime? = null

    @Synchronized
    fun start(config: ShareServerConfig): Result<Unit> {
        val normalized = runCatching {
            validate(config)
            config.normalized().also(::validate)
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
        cleanupTemporaryUploads(root)

        val runtimeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val resources = ShareServerRuntimeResources()
        var httpSocket: ServerSocket? = null
        var ftpSocket: ServerSocket? = null
        try {
            _status.value = ShareServerStatus(ShareServerState.STARTING, normalized)
            if (normalized.httpEnabled) {
                httpSocket = openServerSocket(normalized.httpPort, normalized.bindAddress)
            }
            if (normalized.ftpEnabled) {
                ftpSocket = openServerSocket(normalized.ftpPort, normalized.bindAddress)
            }

            val resolver = ShareServerPathResolver(root.toString())
            val advertisedAddress = if (normalized.bindAddress == ShareServerConfig.LOOPBACK_BIND_ADDRESS) {
                InetAddress.getLoopbackAddress()
            } else {
                localIpv4Addresses().firstOrNull() ?: InetAddress.getLoopbackAddress()
            }
            val httpServer = httpSocket?.let { ShareServerHttpServer(normalized, resolver, it, resources) }
            val ftpServer = ftpSocket?.let {
                ShareServerFtpServer(normalized, resolver, it, advertisedAddress, resources)
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
        require(config.username.length <= 64) { "Username is too long" }
        require(!config.username.contains(':')) { "Username cannot contain ':'" }
        require(config.password.isNotBlank()) { "Password is required" }
        require(config.password.length >= ShareServerConfig.MIN_PASSWORD_LENGTH) {
            "Password must be at least ${ShareServerConfig.MIN_PASSWORD_LENGTH} characters"
        }
        require(config.bindAddress == ShareServerConfig.LOOPBACK_BIND_ADDRESS ||
            config.bindAddress == ShareServerConfig.LAN_BIND_ADDRESS
        ) { "Choose loopback or LAN sharing" }
        if (config.bindAddress == ShareServerConfig.LAN_BIND_ADDRESS) {
            require(config.allowInsecureLan) {
                "LAN sharing requires explicit insecure transport acknowledgement"
            }
        }
    }

    private fun failStart(config: ShareServerConfig, error: Throwable): Result<Unit> {
        diagnosticLog.log(
            provider = "share-server",
            operation = "start",
            error = error,
            detail = "bind=${config.bindAddress}; http=${config.httpEnabled}; ftp=${config.ftpEnabled}",
        )
        _status.value = ShareServerStatus(
            state = ShareServerState.FAILED,
            config = config,
            error = error.message ?: "Unable to start server",
        )
        return Result.failure(error)
    }

    private fun cleanupTemporaryUploads(root: java.nio.file.Path) {
        runCatching {
            Files.walk(root, 32).use { paths ->
                paths.filter { path ->
                    !Files.isSymbolicLink(path) &&
                        path.fileName?.toString()?.startsWith(ShareServerLimits.TEMPORARY_FILE_PREFIX) == true &&
                        Files.isRegularFile(path)
                }.forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }

    private fun openServerSocket(port: Int, bindAddress: String): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), ShareServerLimits.MAX_CONNECTIONS)
    }

    private fun shareAddresses(config: ShareServerConfig, addresses: List<InetAddress>): List<String> {
        val visibleAddresses = if (config.bindAddress == ShareServerConfig.LOOPBACK_BIND_ADDRESS) {
            listOf(InetAddress.getLoopbackAddress())
        } else {
            addresses.ifEmpty { listOf(InetAddress.getLoopbackAddress()) }
        }
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
