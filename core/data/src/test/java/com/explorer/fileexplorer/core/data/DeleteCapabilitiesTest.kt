package com.explorer.fileexplorer.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteCapabilitiesTest {
    @Test
    fun localProviderAdvertisesBestEffortOverwrite() {
        assertEquals(SecureDeleteCapability.BEST_EFFORT, DeleteCapabilities.LOCAL_BEST_EFFORT.secureDelete)
        assertTrue(DeleteCapabilities.LOCAL_BEST_EFFORT.secureDeleteDescription.contains("flash"))
    }

    @Test
    fun providerDefaultDoesNotClaimSecureOverwrite() {
        assertEquals(SecureDeleteCapability.UNSUPPORTED, DeleteCapabilities.PROVIDER_DELETE_ONLY.secureDelete)
        assertTrue(DeleteCapabilities.PROVIDER_DELETE_ONLY.supportsPermanentDelete)
    }
}
