package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.model.CapabilityAssessment
import com.explorer.fileexplorer.core.model.CapabilityStatus
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryFeature
import com.explorer.fileexplorer.core.model.RepositoryOperation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryCapabilityMatrixTest {
    @Test
    fun networkAdvancedOperationsAreMarkedExpensiveAndUnsupportedOperationsStayDisabled() {
        val matrix = RepositoryCapabilities.network(
            provider = "smb",
            advancedOperations = true,
        ).let { com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix.from(it, "smb://nas/share") }

        assertEquals(CapabilityStatus.EXPENSIVE, matrix.operationStatus(RepositoryOperation.SEARCH).status)
        assertTrue(matrix.isActionEnabled(RepositoryOperation.SEARCH))
        assertEquals(CapabilityStatus.EXPENSIVE, matrix.featureStatus(RepositoryFeature.CHECKSUMS).status)
        assertEquals(CapabilityStatus.UNAVAILABLE, matrix.operationStatus(RepositoryOperation.CREATE_FILE).status)
        assertEquals(CapabilityStatus.VERIFIED, matrix.featureStatus(RepositoryFeature.WRITE).status)
        assertEquals(CapabilityStatus.UNAVAILABLE, matrix.featureStatus(RepositoryFeature.ARCHIVE_EXTRACTION).status)
        assertFalse(matrix.isActionEnabled(RepositoryOperation.CREATE_FILE))
    }

    @Test
    fun capabilityStatusIsLocationSpecificForArchiveFormats() {
        val capabilities = RepositoryCapabilities.local("local")
        val rar = com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix.from(capabilities, "/sdcard/archive.rar")
        val zip = com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix.from(capabilities, "/sdcard/archive.zip")

        assertEquals(CapabilityStatus.UNAVAILABLE, rar.featureStatus(RepositoryFeature.ARCHIVE_EXTRACTION).status)
        assertFalse(rar.isActionEnabled(RepositoryFeature.ARCHIVE_EXTRACTION))
        assertEquals(CapabilityStatus.VERIFIED, zip.featureStatus(RepositoryFeature.ARCHIVE_EXTRACTION).status)
    }

    @Test
    fun cloudSignInIsConfigurationGatedUntilTheProviderOverridesReadiness() {
        val base = com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix.from(
            RepositoryCapabilities.cloud("google-drive"),
            "google-drive",
        )
        val verified = base.withFeature(
            RepositoryFeature.CLOUD_SIGN_IN,
            CapabilityAssessment(
                status = CapabilityStatus.VERIFIED,
                reason = "OAuth configuration is verified",
                operation = RepositoryOperation.AUTHENTICATE,
            ),
        )

        assertEquals(
            CapabilityStatus.CONFIGURATION_REQUIRED,
            base.featureStatus(RepositoryFeature.CLOUD_SIGN_IN).status,
        )
        assertFalse(base.isActionEnabled(RepositoryFeature.CLOUD_SIGN_IN))
        assertTrue(verified.isActionEnabled(RepositoryFeature.CLOUD_SIGN_IN))
    }

    @Test
    fun validationAndDiagnosticsUseTheSameAssessment() {
        val matrix = com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix.from(
            RepositoryCapabilities.network("ftp", advancedOperations = false),
            "ftp://host/home",
        )

        assertTrue(matrix.require(RepositoryOperation.LIST).isSuccess)
        val failure = matrix.require(RepositoryOperation.SEARCH)
        assertTrue(failure.isFailure)
        assertContains(failure.exceptionOrNull()?.message.orEmpty(), "search unavailable")
        assertContains(matrix.diagnosticSummary(), "SEARCH:UNAVAILABLE")
    }
}
