package com.explorer.fileexplorer.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trustStore: PluginTrustStore,
    private val auditLog: PluginAuditLog,
) {
    private val packageManager: PackageManager = context.packageManager
    private val callPermits = Semaphore(PluginLimits.MAX_CONCURRENT_CALLS)

    val auditEntries: Flow<List<PluginAuditEntry>> = auditLog.entries

    /** Finds installed plugins without loading plugin code into the host process. */
    fun discover(): List<PluginDescriptor> = runCatching {
        queryPluginServices()
            .mapNotNull { it.toDescriptor() }
            // Duplicate IDs are ambiguous trust identities; fail closed instead of choosing one.
            .groupBy { it.id }
            .values
            .filter { it.size == 1 }
            .flatten()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }.getOrDefault(emptyList())

    fun findByScheme(scheme: String): PluginDescriptor? {
        val normalized = scheme.lowercase(Locale.ROOT)
        return discover().firstOrNull {
            normalized in it.schemes &&
                it.trustState == PluginTrustState.TRUSTED &&
                PluginCapability.FILESYSTEM in it.approvedCapabilities
        }
    }

    /** Approves all capabilities declared by the currently installed component with this ID. */
    fun approve(pluginId: String): Boolean {
        val descriptor = discover().firstOrNull { it.id == pluginId } ?: return false
        val approved = trustStore.approve(descriptor)
        auditLog.record(
            pluginId = pluginId,
            operation = "trust",
            outcome = if (approved) PluginAuditOutcome.APPROVED else PluginAuditOutcome.CALL_REJECTED,
            detail = if (approved) "user approval recorded" else "no verifiable signing certificate or capability",
        )
        return approved
    }

    fun revoke(pluginId: String) {
        trustStore.revoke(pluginId)
        auditLog.record(pluginId, "trust", PluginAuditOutcome.REVOKED, "user approval revoked")
    }

    /** Executes one request in the plugin process and unbinds when the bounded request completes. */
    suspend fun execute(descriptor: PluginDescriptor, request: Bundle): Bundle {
        val operation = request.getString(PluginContract.KEY_OPERATION).orEmpty()
        return try {
            PluginResourcePolicy.validateRequest(request)
            val current = trustedDescriptor(descriptor, operation)
            withTimeout(PluginLimits.CALL_TIMEOUT_MS) {
                callPermits.withPermit {
                    withPlugin(current, request)
                }
            }.also {
                auditLog.record(current.id, operation, PluginAuditOutcome.CALL_SUCCEEDED)
            }
        } catch (error: TimeoutCancellationException) {
            auditLog.record(descriptor.id, operation, PluginAuditOutcome.CALL_TIMED_OUT, "request budget exceeded")
            throw PluginCallException(PluginFailureKind.TIMEOUT, "Plugin request timed out", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PluginCallException) {
            auditLog.record(descriptor.id, operation, outcomeFor(error.kind), error.kind.name.lowercase(Locale.ROOT))
            throw error
        } catch (error: RemoteException) {
            auditLog.record(descriptor.id, operation, PluginAuditOutcome.BINDER_DIED, "remote binder failure")
            throw PluginCallException(PluginFailureKind.BINDER_DIED, "Plugin binder is unavailable", error)
        } catch (error: Throwable) {
            auditLog.record(descriptor.id, operation, PluginAuditOutcome.CALL_FAILED, error.javaClass.simpleName)
            throw error
        }
    }

    private fun trustedDescriptor(
        descriptor: PluginDescriptor,
        operation: String,
    ): PluginDescriptor {
        val requiredCapability = PluginCapability.requiredFor(operation)
            ?: throw PluginCallException(PluginFailureKind.CAPABILITY_DENIED, "Plugin operation is not supported")
        val current = discover().firstOrNull {
            it.id == descriptor.id &&
                it.packageName == descriptor.packageName &&
                it.serviceClassName == descriptor.serviceClassName
        } ?: throw PluginCallException(PluginFailureKind.UNTRUSTED, "Plugin is no longer installed")

        if (current.trustState != PluginTrustState.TRUSTED) {
            throw PluginCallException(PluginFailureKind.UNTRUSTED, "Plugin requires explicit approval")
        }
        if (requiredCapability !in current.capabilities || requiredCapability !in current.approvedCapabilities) {
            throw PluginCallException(PluginFailureKind.CAPABILITY_DENIED, "Plugin capability was not approved")
        }
        return current
    }

    private suspend fun withPlugin(
        descriptor: PluginDescriptor,
        request: Bundle,
    ): Bundle {
        val bound = try {
            withTimeout(PluginLimits.BIND_TIMEOUT_MS) { bind(descriptor) }
        } catch (error: TimeoutCancellationException) {
            throw PluginCallException(PluginFailureKind.TIMEOUT, "Plugin binding timed out", error)
        } catch (error: PluginCallException) {
            throw error
        } catch (error: Throwable) {
            throw PluginCallException(PluginFailureKind.BIND_FAILED, "Unable to bind plugin", error)
        }
        return try {
            val negotiatedVersion = boundedRemoteCall { bound.plugin.getProtocolVersion() }
            if (PluginProtocol.negotiate(negotiatedVersion) == null) {
                throw PluginCallException(
                    PluginFailureKind.PROTOCOL_MISMATCH,
                    "Plugin protocol version is not supported",
                )
            }

            val paths = buildList {
                request.getString(PluginContract.KEY_PATH)?.let(::add)
                request.getStringArrayList(PluginContract.KEY_PATHS)?.let(::addAll)
                request.getString(PluginContract.KEY_DESTINATION)?.let(::add)
            }
            for (path in paths) {
                if (!boundedRemoteCall { bound.plugin.canHandle(path) }) {
                    throw PluginCallException(PluginFailureKind.CAPABILITY_DENIED, "Plugin rejected the requested path")
                }
            }

            val response = boundedRemoteCall { bound.plugin.execute(request) }
            PluginResourcePolicy.validateResponse(response)
            PluginResponses.requireSuccess(response)
        } finally {
            bound.close()
        }
    }

    private suspend fun <T> boundedRemoteCall(block: () -> T): T =
        runInterruptible(Dispatchers.IO) { block() }

    private suspend fun bind(descriptor: PluginDescriptor): BoundPlugin =
        suspendCancellableCoroutine { continuation ->
            val state = BindingState(context)
            var connection: ServiceConnection? = null
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val activeConnection = connection ?: return
                    if (!continuation.isActive) {
                        state.unbind(activeConnection)
                        return
                    }
                    continuation.resume(BoundPlugin(IFileExplorerPlugin.Stub.asInterface(service)) {
                        state.unbind(activeConnection)
                    })
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (continuation.isActive) {
                        connection?.let(state::unbind)
                        continuation.resumeWithException(
                            PluginCallException(PluginFailureKind.BINDER_DIED, "Plugin service disconnected"),
                        )
                    }
                }

                override fun onBindingDied(name: ComponentName) {
                    if (continuation.isActive) {
                        connection?.let(state::unbind)
                        continuation.resumeWithException(
                            PluginCallException(PluginFailureKind.BINDER_DIED, "Plugin binding died"),
                        )
                    }
                }

                override fun onNullBinding(name: ComponentName) {
                    if (continuation.isActive) {
                        connection?.let(state::unbind)
                        continuation.resumeWithException(
                            PluginCallException(PluginFailureKind.BIND_FAILED, "Plugin returned no binder"),
                        )
                    }
                }
            }

            try {
                val activeConnection = connection ?: error("Plugin connection was not created")
                state.bound = context.bindService(
                    Intent(PluginContract.ACTION_PLUGIN).setComponent(
                        ComponentName(descriptor.packageName, descriptor.serviceClassName),
                    ),
                    activeConnection,
                    Context.BIND_AUTO_CREATE,
                )
                if (!state.bound) {
                    continuation.resumeWithException(
                        PluginCallException(PluginFailureKind.BIND_FAILED, "Unable to bind plugin"),
                    )
                } else {
                    continuation.invokeOnCancellation { state.unbind(activeConnection) }
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        error as? PluginCallException
                            ?: PluginCallException(PluginFailureKind.BIND_FAILED, "Unable to bind plugin", error),
                    )
                }
            }
        }

    private fun queryPluginServices(): List<ResolveInfo> {
        val intent = Intent(PluginContract.ACTION_PLUGIN)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
    }

    private fun ResolveInfo.toDescriptor(): PluginDescriptor? {
        val service = serviceInfo ?: return null
        if (!service.exported) return null
        val metadata = service.metaData ?: return null
        val applicationLabel = service.applicationInfo?.loadLabel(packageManager)?.toString().orEmpty()
        val descriptor = PluginDescriptorCodec.decode(
            PluginMetadata(
                protocolVersion = metadata.getInt(PluginContract.META_PROTOCOL_VERSION, -1),
                id = metadata.getString(PluginContract.META_ID).orEmpty(),
                displayName = metadata.getString(PluginContract.META_DISPLAY_NAME)
                    ?.takeIf { it.isNotBlank() } ?: applicationLabel,
                versionName = metadata.getString(PluginContract.META_VERSION_NAME).orEmpty(),
                packageName = service.packageName,
                serviceClassName = service.name,
                schemes = metadata.getString(PluginContract.META_SCHEMES),
                capabilities = metadata.getString(PluginContract.META_CAPABILITIES),
            ),
        ) ?: return null
        val signatureDigest = signingCertificateDigest(service.packageName) ?: return descriptor
        val signedDescriptor = descriptor.copy(signatureDigest = signatureDigest)
        return signedDescriptor.copy(
            trustState = trustStore.state(signedDescriptor),
            approvedCapabilities = trustStore.approvedCapabilities(signedDescriptor),
        )
    }

    private fun signingCertificateDigest(packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        signatures
            .map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex() }
            .sorted()
            .joinToString(",")
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun outcomeFor(kind: PluginFailureKind): PluginAuditOutcome = when (kind) {
        PluginFailureKind.TIMEOUT -> PluginAuditOutcome.CALL_TIMED_OUT
        PluginFailureKind.BINDER_DIED -> PluginAuditOutcome.BINDER_DIED
        else -> PluginAuditOutcome.CALL_REJECTED
    }

    private class BindingState(private val context: Context) {
        private val unbound = AtomicBoolean(false)
        var bound: Boolean = false

        fun unbind(connection: ServiceConnection) {
            if (bound && unbound.compareAndSet(false, true)) {
                runCatching { context.unbindService(connection) }
            }
        }
    }

    private data class BoundPlugin(
        val plugin: IFileExplorerPlugin,
        val close: () -> Unit,
    )
}
