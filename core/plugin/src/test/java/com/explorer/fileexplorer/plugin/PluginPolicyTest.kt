package com.explorer.fileexplorer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginPolicyTest {
    @Test
    fun knownOperationsRequireFilesystemCapability() {
        assertEquals(PluginCapability.FILESYSTEM, PluginCapability.requiredFor(PluginContract.OP_LIST))
        assertEquals(PluginCapability.FILESYSTEM, PluginCapability.requiredFor(PluginContract.OP_CHECKSUM))
        assertEquals(null, PluginCapability.requiredFor("future_operation"))
    }

    @Test
    fun protocolNegotiationFailsClosedForUnknownVersions() {
        assertEquals(PluginContract.PROTOCOL_VERSION, PluginProtocol.negotiate(PluginContract.PROTOCOL_VERSION))
        assertEquals(null, PluginProtocol.negotiate(PluginContract.PROTOCOL_VERSION + 1))
    }

    @Test
    fun unknownOperationsHaveNoCapability() {
        assertEquals(null, PluginCapability.requiredFor("future_operation"))
    }

    @Test
    fun pathPolicyRejectsTooManyPathsBeforeBinding() {
        val error = assertFailsWith<PluginCallException> {
            PluginResourcePolicy.validatePathInputs(
                paths = List(PluginLimits.MAX_PATHS + 1) { "/path/$it" },
                query = null,
                algorithm = null,
            )
        }

        assertEquals(PluginFailureKind.RESOURCE_LIMIT, error.kind)
    }

    @Test
    fun requestPolicyRejectsOverlongPathsBeforeBinding() {
        val error = assertFailsWith<PluginCallException> {
            PluginResourcePolicy.validatePathInputs(
                paths = listOf("x".repeat(PluginLimits.MAX_STRING_CHARS + 1)),
                query = null,
                algorithm = null,
            )
        }

        assertEquals(PluginFailureKind.RESOURCE_LIMIT, error.kind)
    }
}
