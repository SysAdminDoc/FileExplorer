package com.explorer.fileexplorer.core.cloud

import android.content.Intent
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.CapabilityStatus
import com.explorer.fileexplorer.core.model.RepositoryFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudProviderStatusTest {
    @Test
    fun missingProviderIsUnavailable() {
        val status = resolveCloudServiceStatus(CloudService.GOOGLE_DRIVE, null, emptyList())

        assertEquals(CloudAuthState.UNAVAILABLE, status.state)
        assertEquals(
            CapabilityStatus.UNAVAILABLE,
            status.capabilityMatrix.featureStatus(RepositoryFeature.WRITE).status,
        )
    }

    @Test
    fun configuredProviderIsVerifiedUntilAnAccountExists() {
        val provider = FakeProvider(CloudAuthState.VERIFIED)

        val status = resolveCloudServiceStatus(provider.service, provider, emptyList())

        assertEquals(CloudAuthState.VERIFIED, status.state)
        assertEquals(
            CapabilityStatus.VERIFIED,
            status.capabilityMatrix.featureStatus(RepositoryFeature.CLOUD_SIGN_IN).status,
        )
        assertEquals(
            CapabilityStatus.CONFIGURATION_REQUIRED,
            status.capabilityMatrix.featureStatus(RepositoryFeature.WRITE).status,
        )
    }

    @Test
    fun accountWithTokenIsSignedIn() {
        val provider = FakeProvider(CloudAuthState.VERIFIED)
        val account = CloudAccount(
            id = "account-1",
            service = provider.service,
            email = "user@example.com",
            displayName = "User",
            accessToken = "access-token",
        )

        val status = resolveCloudServiceStatus(provider.service, provider, listOf(account))

        assertEquals(CloudAuthState.SIGNED_IN, status.state)
        assertEquals(
            CapabilityStatus.VERIFIED,
            status.capabilityMatrix.featureStatus(RepositoryFeature.CLOUD_SIGN_IN).status,
        )
        assertEquals(
            CapabilityStatus.VERIFIED,
            status.capabilityMatrix.featureStatus(RepositoryFeature.WRITE).status,
        )
    }

    private class FakeProvider(
        override val readiness: CloudAuthState,
    ) : CloudProvider {
        override val service: CloudService = CloudService.GOOGLE_DRIVE
        override val isAuthenticated: Boolean = false

        override suspend fun getAuthIntent(): Intent? = Intent("test.auth")
        override suspend fun handleAuthResult(data: Intent): Result<CloudAccount> =
            Result.failure(UnsupportedOperationException())
        override suspend fun refreshToken(account: CloudAccount): Result<CloudAccount> =
            Result.failure(UnsupportedOperationException())
        override suspend fun signOut(account: CloudAccount): Result<Unit> = Result.success(Unit)
        override fun listFiles(account: CloudAccount, folderId: String): Flow<List<FileItem>> = emptyFlow()
        override suspend fun download(
            account: CloudAccount,
            fileId: String,
            localPath: String,
            onProgress: (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun upload(
            account: CloudAccount,
            localPath: String,
            parentFolderId: String,
            onProgress: (Long, Long) -> Unit,
        ): Result<FileItem> = Result.failure(UnsupportedOperationException())
        override suspend fun delete(account: CloudAccount, fileId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
        override suspend fun createFolder(account: CloudAccount, name: String, parentId: String): Result<FileItem> =
            Result.failure(UnsupportedOperationException())
        override suspend fun rename(account: CloudAccount, fileId: String, newName: String): Result<FileItem> =
            Result.failure(UnsupportedOperationException())
        override suspend fun getQuota(account: CloudAccount): Result<Pair<Long, Long>> = Result.success(0L to 0L)
    }
}
