package com.explorer.fileexplorer.core.cloud

import android.content.Context
import android.util.Log
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.model.RepositoryCapabilities
import com.explorer.fileexplorer.core.storage.CredentialCipher
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
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

enum class CloudAuthState {
    VERIFIED,
    REQUIRES_CONFIGURATION,
    UNAVAILABLE,
    SIGNED_IN,
}

data class CloudServiceStatus(
    val service: CloudService,
    val state: CloudAuthState,
)

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
    val staySignedIn: Boolean = false,
)

/** Abstract cloud storage provider contract. */
interface CloudProvider {
    val service: CloudService
    val isAuthenticated: Boolean

    /** Supported cloud operations and their stable provider identity. */
    val capabilities: RepositoryCapabilities
        get() = RepositoryCapabilities.cloud(service.name.lowercase())

    /** Readiness of the provider implementation before an account is signed in. */
    val readiness: CloudAuthState
        get() = if (isAuthenticated) CloudAuthState.SIGNED_IN else CloudAuthState.REQUIRES_CONFIGURATION

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
    suspend fun getQuota(account: CloudAccount): Result<Pair<Long, Long>>
}

fun resolveCloudServiceStatus(
    service: CloudService,
    provider: CloudProvider?,
    accounts: List<CloudAccount>,
): CloudServiceStatus {
    if (provider == null) return CloudServiceStatus(service, CloudAuthState.UNAVAILABLE)
    val hasToken = accounts.any {
        it.service == service && (it.accessToken.isNotBlank() || it.refreshToken.isNotBlank())
    }
    return CloudServiceStatus(
        service = service,
        state = if (hasToken) CloudAuthState.SIGNED_IN else provider.readiness,
    )
}

/** Manages cloud accounts across all providers. */
@Singleton
class CloudAccountManager @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialCipher: CredentialCipher,
) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val accountListType = object : TypeToken<List<CloudAccount>>() {}.type

    private val _accounts = MutableStateFlow(loadPersistedAccounts())
    val accounts: StateFlow<List<CloudAccount>> = _accounts.asStateFlow()

    private val providers = mutableMapOf<CloudService, CloudProvider>()

    fun registerProvider(provider: CloudProvider) {
        providers[provider.service] = provider
    }

    fun getProvider(service: CloudService): CloudProvider? = providers[service]

    fun statusFor(service: CloudService): CloudServiceStatus =
        resolveCloudServiceStatus(service, providers[service], _accounts.value)

    fun statuses(): Map<CloudService, CloudServiceStatus> =
        CloudService.entries.associateWith(::statusFor)

    fun addAccount(account: CloudAccount, staySignedIn: Boolean = account.staySignedIn): Result<Unit> = runCatching {
        _accounts.value = _accounts.value.filter { it.id != account.id } + account.copy(staySignedIn = staySignedIn)
        persistAccounts()
    }

    fun removeAccount(accountId: String): Result<Unit> = runCatching {
        _accounts.value = _accounts.value.filter { it.id != accountId }
        persistAccounts()
    }

    fun setStaySignedIn(accountId: String, enabled: Boolean): Result<Unit> = runCatching {
        _accounts.value = _accounts.value.map { account ->
            if (account.id == accountId) account.copy(staySignedIn = enabled) else account
        }
        persistAccounts()
    }

    fun getAccount(accountId: String): CloudAccount? {
        return _accounts.value.firstOrNull { it.id == accountId }
    }

    fun getAccountsForService(service: CloudService): List<CloudAccount> {
        return _accounts.value.filter { it.service == service }
    }

    private fun persistAccounts() {
        val persistedAccounts = _accounts.value.filter { it.staySignedIn }
        if (persistedAccounts.isEmpty()) {
            preferences.edit().remove(PREF_ACCOUNTS).apply()
            return
        }

        val encryptedPayload = credentialCipher.encrypt(gson.toJson(persistedAccounts))
        preferences.edit().putString(PREF_ACCOUNTS, encryptedPayload).apply()
    }

    private fun loadPersistedAccounts(): List<CloudAccount> {
        val payload = preferences.getString(PREF_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val json = credentialCipher.decrypt(payload)
            gson.fromJson<List<CloudAccount>>(json, accountListType)
                .orEmpty()
                .map { it.copy(staySignedIn = true) }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read saved cloud accounts", error)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "CloudAccountManager"
        const val PREFERENCES_NAME = "cloud_accounts"
        const val PREF_ACCOUNTS = "encrypted_accounts"
    }
}
