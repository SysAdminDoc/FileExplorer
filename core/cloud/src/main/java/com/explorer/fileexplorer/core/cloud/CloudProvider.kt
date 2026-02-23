package com.explorer.fileexplorer.core.cloud

import com.explorer.fileexplorer.core.model.FileItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Supported cloud providers. */
enum class CloudService(val displayName: String, val iconName: String) {
    GOOGLE_DRIVE("Google Drive", "google_drive"),
    DROPBOX("Dropbox", "dropbox"),
    ONEDRIVE("OneDrive", "onedrive"),
}

/** Persisted cloud account info. */
data class CloudAccount(
    val id: String,
    val service: CloudService,
    val email: String,
    val displayName: String,
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenExpiry: Long = 0L,
    val quotaTotal: Long = 0L,
    val quotaUsed: Long = 0L,
)

/** Abstract cloud storage provider contract. */
interface CloudProvider {
    val service: CloudService
    val isAuthenticated: Boolean

    /** Begin OAuth flow — returns an Intent to launch. */
    suspend fun getAuthIntent(): android.content.Intent?

    /** Handle OAuth callback, exchange code for tokens. */
    suspend fun handleAuthResult(data: android.content.Intent): Result<CloudAccount>

    /** Refresh expired access token. */
    suspend fun refreshToken(account: CloudAccount): Result<CloudAccount>

    /** Sign out and revoke tokens. */
    suspend fun signOut(account: CloudAccount): Result<Unit>

    /** List files in a cloud folder. */
    fun listFiles(account: CloudAccount, folderId: String = "root"): Flow<List<FileItem>>

    /** Download a cloud file to local path. */
    suspend fun download(
        account: CloudAccount, fileId: String, localPath: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Unit>

    /** Upload a local file to cloud folder. */
    suspend fun upload(
        account: CloudAccount, localPath: String, parentFolderId: String = "root",
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<FileItem>

    /** Delete a cloud file. */
    suspend fun delete(account: CloudAccount, fileId: String): Result<Unit>

    /** Create a folder in cloud. */
    suspend fun createFolder(account: CloudAccount, name: String, parentId: String = "root"): Result<FileItem>

    /** Rename a cloud item. */
    suspend fun rename(account: CloudAccount, fileId: String, newName: String): Result<FileItem>

    /** Get storage quota. */
    suspend fun getQuota(account: CloudAccount): Pair<Long, Long>
}

/** Manages cloud accounts across all providers. */
@Singleton
class CloudAccountManager @Inject constructor() {

    private val _accounts = MutableStateFlow<List<CloudAccount>>(emptyList())
    val accounts: StateFlow<List<CloudAccount>> = _accounts.asStateFlow()

    private val providers = mutableMapOf<CloudService, CloudProvider>()

    fun registerProvider(provider: CloudProvider) {
        providers[provider.service] = provider
    }

    fun getProvider(service: CloudService): CloudProvider? = providers[service]

    fun addAccount(account: CloudAccount) {
        _accounts.value = _accounts.value.filter { it.id != account.id } + account
    }

    fun removeAccount(accountId: String) {
        _accounts.value = _accounts.value.filter { it.id != accountId }
    }

    fun getAccount(accountId: String): CloudAccount? {
        return _accounts.value.firstOrNull { it.id == accountId }
    }

    fun getAccountsForService(service: CloudService): List<CloudAccount> {
        return _accounts.value.filter { it.service == service }
    }
}
