package com.explorer.fileexplorer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginDescriptorCodecTest {
    @Test
    fun decodesAndNormalizesManifestMetadata() {
        val descriptor = PluginDescriptorCodec.decode(
            PluginMetadata(
                protocolVersion = PluginContract.PROTOCOL_VERSION,
                id = "com.example.sftp",
                displayName = "  SFTP add-on  ",
                versionName = "1.2.0",
                packageName = "com.example.sftp",
                serviceClassName = "com.example.sftp.PluginService",
                schemes = "SFTP, sftp; sftp+ssh",
                capabilities = "filesystem,tool",
            ),
        )

        checkNotNull(descriptor)
        assertEquals("SFTP add-on", descriptor.displayName)
        assertEquals(setOf("sftp", "sftp+ssh"), descriptor.schemes)
        assertEquals(setOf(PluginCapability.FILESYSTEM, PluginCapability.TOOL), descriptor.capabilities)
    }

    @Test
    fun rejectsUnsupportedProtocolAndInvalidSchemes() {
        val unsupported = baseMetadata(protocolVersion = PluginContract.PROTOCOL_VERSION + 1)
        val invalidScheme = baseMetadata(schemes = "smb://not-a-scheme")

        assertNull(PluginDescriptorCodec.decode(unsupported))
        assertNull(PluginDescriptorCodec.decode(invalidScheme))
    }

    private fun baseMetadata(
        protocolVersion: Int = PluginContract.PROTOCOL_VERSION,
        schemes: String? = "example",
    ) = PluginMetadata(
        protocolVersion = protocolVersion,
        id = "com.example.plugin",
        displayName = "Example",
        versionName = "1.0",
        packageName = "com.example.plugin",
        serviceClassName = "com.example.plugin.Service",
        schemes = schemes,
        capabilities = "filesystem",
    )
}
