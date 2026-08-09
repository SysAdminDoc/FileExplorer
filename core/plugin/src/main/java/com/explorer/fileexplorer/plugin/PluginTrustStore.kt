package com.explorer.fileexplorer.plugin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists explicit component and signing-certificate approval for third-party plugins.
 * This is a trust decision, not a secret, so it intentionally remains separate from credential storage.
 */
@Singleton
class PluginTrustStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun state(descriptor: PluginDescriptor): PluginTrustState {
        val record = read(descriptor.id) ?: return PluginTrustState.UNTRUSTED
        return if (record.matches(descriptor)) {
            PluginTrustState.TRUSTED
        } else {
            PluginTrustState.SIGNATURE_CHANGED
        }
    }

    fun approvedCapabilities(descriptor: PluginDescriptor): Set<PluginCapability> {
        val record = read(descriptor.id) ?: return emptySet()
        if (!record.matches(descriptor)) return emptySet()
        return record.capabilities.intersect(descriptor.capabilities)
    }

    /** Approves the requested capabilities for this exact component and certificate. */
    fun approve(
        descriptor: PluginDescriptor,
        capabilities: Set<PluginCapability> = descriptor.capabilities,
    ): Boolean {
        if (descriptor.signatureDigest.isBlank()) return false
        val approved = capabilities.intersect(descriptor.capabilities)
        if (approved.isEmpty()) return false
        return preferences.edit()
            .putString(
                key(descriptor.id),
                listOf(
                    descriptor.packageName,
                    descriptor.serviceClassName,
                    descriptor.signatureDigest,
                    approved.joinToString(",") { it.wireName },
                ).joinToString(RECORD_SEPARATOR),
            )
            .commit()
    }

    fun revoke(pluginId: String) {
        preferences.edit().remove(key(pluginId)).commit()
    }

    fun isApproved(descriptor: PluginDescriptor, capability: PluginCapability): Boolean =
        state(descriptor) == PluginTrustState.TRUSTED && capability in approvedCapabilities(descriptor)

    private fun read(pluginId: String): TrustRecord? {
        val encoded = preferences.getString(key(pluginId), null) ?: return null
        val fields = encoded.split(RECORD_SEPARATOR)
        if (fields.size != 4) return null
        val capabilities = fields[3]
            .split(',')
            .mapNotNull(PluginCapability::fromWireName)
            .toSet()
        return TrustRecord(
            packageName = fields[0],
            serviceClassName = fields[1],
            signatureDigest = fields[2],
            capabilities = capabilities,
        )
    }

    private fun key(pluginId: String): String = "consent.$pluginId"

    private data class TrustRecord(
        val packageName: String,
        val serviceClassName: String,
        val signatureDigest: String,
        val capabilities: Set<PluginCapability>,
    ) {
        fun matches(descriptor: PluginDescriptor): Boolean =
            packageName == descriptor.packageName &&
                serviceClassName == descriptor.serviceClassName &&
                signatureDigest == descriptor.signatureDigest
    }

    private companion object {
        const val PREFERENCES_NAME = "plugin_trust"
        const val RECORD_SEPARATOR = "\u001f"
    }
}
