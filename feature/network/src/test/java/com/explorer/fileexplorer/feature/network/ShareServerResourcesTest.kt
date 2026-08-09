package com.explorer.fileexplorer.feature.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareServerResourcesTest {

    @Test
    fun connectionPermitsAreBoundedAndReusable() {
        val resources = ShareServerRuntimeResources(maxConnections = 2)

        assertTrue(resources.tryAcquireConnection())
        assertTrue(resources.tryAcquireConnection())
        assertFalse(resources.tryAcquireConnection())

        resources.releaseConnection()
        assertTrue(resources.tryAcquireConnection())
    }

    @Test
    fun temporaryStorageReservationsCannotExceedBudget() {
        val resources = ShareServerRuntimeResources(maxTemporaryBytes = 10)

        assertTrue(resources.tryReserveTemporary(6))
        assertFalse(resources.tryReserveTemporary(5))
        resources.releaseTemporary(6)
        assertTrue(resources.tryReserveTemporary(10))
    }

    @Test
    fun requestRateIsBoundedPerClient() {
        val resources = ShareServerRuntimeResources(maxRequestsPerMinute = 2)

        assertTrue(resources.allowRequest("127.0.0.1"))
        assertTrue(resources.allowRequest("127.0.0.1"))
        assertFalse(resources.allowRequest("127.0.0.1"))
        assertTrue(resources.allowRequest("192.0.2.1"))
    }
}
