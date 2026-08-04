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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    /** Finds installed plugins without loading plugin code into the host process. */
    fun discover(): List<PluginDescriptor> = runCatching {
        queryPluginServices().mapNotNull { it.toDescriptor() }
            .distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }.getOrDefault(emptyList())

    fun findByScheme(scheme: String): PluginDescriptor? {
        val normalized = scheme.lowercase()
        return discover().firstOrNull { normalized in it.schemes }
    }

    /** Executes one request in the plugin process and unbinds when the request completes. */
    suspend fun execute(descriptor: PluginDescriptor, request: Bundle): Bundle {
        return withPlugin(descriptor) { plugin ->
            val path = request.getString(PluginContract.KEY_PATH)
                ?: request.getStringArrayList(PluginContract.KEY_PATHS)?.firstOrNull()
            check(path == null || plugin.canHandle(path)) {
                "Plugin ${descriptor.id} cannot handle $path"
            }
            PluginResponses.requireSuccess(plugin.execute(request))
        }
    }

    private suspend fun <T> withPlugin(
        descriptor: PluginDescriptor,
        block: (IFileExplorerPlugin) -> T,
    ): T {
        val bound = bind(descriptor)
        return try {
            val protocolVersion = bound.plugin.getProtocolVersion()
            check(protocolVersion == PluginContract.PROTOCOL_VERSION) {
                "Unsupported plugin protocol $protocolVersion"
            }
            block(bound.plugin)
        } finally {
            bound.close()
        }
    }

    private suspend fun bind(descriptor: PluginDescriptor): BoundPlugin =
        suspendCancellableCoroutine { continuation ->
            val state = BindingState(context)
            var connection: ServiceConnection? = null
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!continuation.isActive) return
                    val activeConnection = connection ?: return
                    continuation.resume(BoundPlugin(IFileExplorerPlugin.Stub.asInterface(service)) {
                        state.unbind(activeConnection)
                    })
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Plugin service disconnected"))
                    }
                }

                override fun onBindingDied(name: ComponentName) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Plugin binding died"))
                    }
                }
            }

            val intent = Intent(PluginContract.ACTION_PLUGIN).setComponent(
                ComponentName(descriptor.packageName, descriptor.serviceClassName),
            )
            try {
                val activeConnection = connection ?: error("Plugin connection was not created")
                state.bound = context.bindService(intent, activeConnection, Context.BIND_AUTO_CREATE)
                if (!state.bound) {
                    continuation.resumeWithException(IllegalStateException("Unable to bind plugin ${descriptor.id}"))
                } else {
                    continuation.invokeOnCancellation { state.unbind(activeConnection) }
                }
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
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
        val metadata = service.metaData ?: return null
        val applicationLabel = service.applicationInfo?.loadLabel(packageManager)?.toString().orEmpty()
        return PluginDescriptorCodec.decode(
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
        )
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
