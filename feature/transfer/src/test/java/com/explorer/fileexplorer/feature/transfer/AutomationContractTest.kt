package com.explorer.fileexplorer.feature.transfer

import com.explorer.fileexplorer.core.data.ArchiveFormat
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.model.RepositoryCapabilityMatrix
import com.explorer.fileexplorer.core.model.RepositoryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomationContractTest {
    @Test
    fun parsesZipWithDefaultFormatAndNormalizesPaths() {
        val request = AutomationContract.parse(
            action = AutomationContract.ACTION_ZIP,
            sourcePaths = listOf(" /sdcard/a.txt ", "/sdcard/a.txt", " /sdcard/b.txt"),
            destination = " /sdcard/archive.zip ",
        ).getOrThrow()

        assertEquals(AutomationContract.Operation.ZIP, request.operation)
        assertEquals(listOf("/sdcard/a.txt", "/sdcard/b.txt"), request.sourcePaths)
        assertEquals("/sdcard/archive.zip", request.destination)
        assertEquals(ArchiveFormat.ZIP, request.archiveFormat)
        assertEquals(ConflictResolution.RENAME, request.conflictResolution)
    }

    @Test
    fun parsesAlternateArchiveFormatsAndConflictModes() {
        val request = AutomationContract.parse(
            action = AutomationContract.ACTION_ZIP,
            sourcePaths = listOf("/sdcard/source"),
            destination = "/sdcard/archive.7z",
            format = ".7z",
            conflict = "overwrite",
        ).getOrThrow()

        assertEquals(ArchiveFormat.SEVEN_Z, request.archiveFormat)
        assertEquals(ConflictResolution.OVERWRITE, request.conflictResolution)
    }

    @Test
    fun uploadRequiresSavedConnectionId() {
        val result = AutomationContract.parse(
            action = AutomationContract.ACTION_UPLOAD,
            sourcePaths = listOf("/sdcard/source.txt"),
            destination = "/incoming/source.txt",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("connection_id"))
    }

    @Test
    fun uploadCarriesConnectionId() {
        val request = AutomationContract.parse(
            action = AutomationContract.ACTION_UPLOAD,
            sourcePaths = listOf("/sdcard/source.txt"),
            destination = "/incoming/source.txt",
            connectionId = 42L,
        ).getOrThrow()

        assertEquals(AutomationContract.Operation.UPLOAD, request.operation)
        assertEquals(42L, request.connectionId)
    }

    @Test
    fun rejectsInteractiveConflictModeAndUnknownFormat() {
        val interactive = AutomationContract.parse(
            action = AutomationContract.ACTION_COPY,
            sourcePaths = listOf("/sdcard/source"),
            destination = "/sdcard/destination",
            conflict = "ask",
        )
        assertTrue(interactive.isFailure)
        assertTrue(runCatching { AutomationContract.parseArchiveFormat("rar") }.isFailure)
    }

    @Test
    fun keepBothCarriesAStablePolicyKey() {
        val first = AutomationContract.parse(
            action = AutomationContract.ACTION_COPY,
            sourcePaths = listOf("/sdcard/source.txt"),
            destination = "/sdcard/destination",
            conflict = "keep-both",
            idempotencyKey = "workflow-42",
        ).getOrThrow()
        val second = AutomationContract.parse(
            action = AutomationContract.ACTION_COPY,
            sourcePaths = listOf("/sdcard/source.txt"),
            destination = "/sdcard/destination",
            conflict = "keep-both",
            idempotencyKey = "workflow-42",
        ).getOrThrow()

        assertEquals(ConflictResolution.RENAME, first.conflictResolution)
        assertEquals(true, first.deterministicKeepBoth)
        assertEquals(first.idempotencyKey, second.idempotencyKey)
    }

    @Test
    fun derivesAnIdempotencyKeyWhenAutomationOmitsOne() {
        val first = AutomationContract.parse(
            action = AutomationContract.ACTION_COPY,
            sourcePaths = listOf("/sdcard/source.txt"),
            destination = "/sdcard/destination",
        ).getOrThrow()
        val second = AutomationContract.parse(
            action = AutomationContract.ACTION_COPY,
            sourcePaths = listOf("/sdcard/source.txt"),
            destination = "/sdcard/destination",
        ).getOrThrow()

        assertEquals(first.idempotencyKey, second.idempotencyKey)
    }

    @Test
    fun capabilityValidationRejectsUnavailableRemoteSearchAndAllowsSupportedCopy() {
        val network = RepositoryCapabilityMatrix.from(
            RepositoryCapabilities.network("ftp"),
            "ftp://host/home",
        )
        val request = AutomationContract.parse(
            action = AutomationContract.ACTION_COPY,
            sourcePaths = listOf("ftp://host/home/source"),
            destination = "ftp://host/home/destination",
        ).getOrThrow()

        assertTrue(AutomationContract.validateCapabilities(request, network, network).isSuccess)
        assertTrue(network.require(RepositoryOperation.SEARCH).isFailure)
    }

    @Test
    fun capabilityValidationRejectsArchiveCreationOutsideLocalStorage() {
        val network = RepositoryCapabilityMatrix.from(
            RepositoryCapabilities.network("smb"),
            "smb://host/share/archive.zip",
        )
        val request = AutomationContract.parse(
            action = AutomationContract.ACTION_ZIP,
            sourcePaths = listOf("smb://host/share/source"),
            destination = "smb://host/share/archive.zip",
        ).getOrThrow()

        val result = AutomationContract.validateCapabilities(request, network, network)
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().lowercase().contains("archive creation unavailable"),
        )
    }
}
