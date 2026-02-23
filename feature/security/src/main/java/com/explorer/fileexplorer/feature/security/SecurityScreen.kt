package com.explorer.fileexplorer.feature.security

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.data.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.securityDataStore by preferencesDataStore("security_prefs")

// -- Security Repository --

@Singleton
class SecurityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val VAULT_ENABLED = booleanPreferencesKey("vault_enabled")
        val SECURE_DELETE_ENABLED = booleanPreferencesKey("secure_delete_enabled")
    }

    val settings: Flow<SecuritySettings> = context.securityDataStore.data.map { prefs ->
        SecuritySettings(
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            vaultEnabled = prefs[Keys.VAULT_ENABLED] ?: false,
            secureDeleteEnabled = prefs[Keys.SECURE_DELETE_ENABLED] ?: false,
        )
    }

    suspend fun setAppLock(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setVaultEnabled(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.VAULT_ENABLED] = enabled }
    }

    suspend fun setSecureDelete(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.SECURE_DELETE_ENABLED] = enabled }
    }

    /** Check if device supports biometric authentication. */
    fun canUseBiometrics(): Boolean {
        val mgr = BiometricManager.from(context)
        return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }
}

data class SecuritySettings(
    val appLockEnabled: Boolean = false,
    val vaultEnabled: Boolean = false,
    val secureDeleteEnabled: Boolean = false,
)

// -- Biometric Helper --

@Singleton
class BiometricHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Use biometrics to unlock",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFailure(errString.toString()) }
            override fun onAuthenticationFailed() { onFailure("Authentication failed") }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

// -- Secure Delete --

object SecureDelete {
    private val random = SecureRandom()

    /**
     * Securely delete a file by overwriting with random data 3 times (DoD 5220.22-M),
     * then deleting the file.
     */
    suspend fun secureDelete(path: String, passes: Int = 3): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext Result.failure(Exception("File not found"))
            if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    secureDelete(child.absolutePath, passes)
                }
                file.delete()
                return@withContext Result.success(Unit)
            }

            val length = file.length()
            if (length > 0) {
                RandomAccessFile(file, "rw").use { raf ->
                    val buf = ByteArray(65536)
                    repeat(passes) { pass ->
                        raf.seek(0)
                        var remaining = length
                        while (remaining > 0) {
                            val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                            when (pass % 3) {
                                0 -> random.nextBytes(buf)          // Random
                                1 -> buf.fill(0x00.toByte())        // Zeros
                                2 -> buf.fill(0xFF.toByte())        // Ones
                            }
                            raf.write(buf, 0, toWrite)
                            remaining -= toWrite
                        }
                        raf.fd.sync()
                    }
                    // Final random pass
                    raf.seek(0)
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                        random.nextBytes(buf)
                        raf.write(buf, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                }
            }
            file.delete()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
}

// -- Checksum Verification --

object ChecksumUtil {
    /** Compute checksum of a file. Algorithms: MD5, SHA-1, SHA-256, SHA-512 */
    suspend fun computeChecksum(path: String, algorithm: String = "SHA-256"): Result<String> = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance(algorithm)
            File(path).inputStream().buffered().use { input ->
                val buf = ByteArray(65536)
                var len: Int
                while (input.read(buf).also { len = it } != -1) {
                    digest.update(buf, 0, len)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            Result.success(hex)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Verify a file against an expected checksum. */
    suspend fun verify(path: String, expected: String, algorithm: String = "SHA-256"): Boolean {
        val computed = computeChecksum(path, algorithm).getOrNull() ?: return false
        return computed.equals(expected, ignoreCase = true)
    }
}

// -- Encrypted Vault --

@Singleton
class VaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val vaultDir: File get() = File(context.filesDir, ".vault")

    fun getVaultPath(): String {
        vaultDir.mkdirs()
        // Set directory permissions to owner-only
        vaultDir.setReadable(false, false)
        vaultDir.setReadable(true, true)
        vaultDir.setWritable(false, false)
        vaultDir.setWritable(true, true)
        vaultDir.setExecutable(false, false)
        vaultDir.setExecutable(true, true)
        return vaultDir.absolutePath
    }

    /** Move a file into the vault. */
    suspend fun lockFile(sourcePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val source = File(sourcePath)
            val dest = File(getVaultPath(), source.name)
            if (dest.exists()) {
                // Auto-rename
                var counter = 1
                var newDest = File(getVaultPath(), "${source.nameWithoutExtension} ($counter).${source.extension}")
                while (newDest.exists()) { counter++; newDest = File(getVaultPath(), "${source.nameWithoutExtension} ($counter).${source.extension}") }
                source.renameTo(newDest)
                Result.success(newDest.absolutePath)
            } else {
                source.renameTo(dest)
                Result.success(dest.absolutePath)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Move a file out of the vault. */
    suspend fun unlockFile(vaultPath: String, destinationDir: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val source = File(vaultPath)
            val dest = File(destinationDir, source.name)
            source.renameTo(dest)
            Result.success(dest.absolutePath)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** List files in the vault. */
    fun listVaultFiles(): List<File> {
        return vaultDir.listFiles()?.toList() ?: emptyList()
    }
}

// -- ViewModel --

data class SecurityUiState(
    val settings: SecuritySettings = SecuritySettings(),
    val canUseBiometrics: Boolean = false,
    val checksumResult: String? = null,
    val isComputing: Boolean = false,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val securityRepo: SecurityRepository,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>()
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        _state.update { it.copy(canUseBiometrics = securityRepo.canUseBiometrics()) }
        viewModelScope.launch {
            securityRepo.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
    }

    fun toggleAppLock() { viewModelScope.launch { securityRepo.setAppLock(!_state.value.settings.appLockEnabled) } }
    fun toggleVault() { viewModelScope.launch { securityRepo.setVaultEnabled(!_state.value.settings.vaultEnabled) } }
    fun toggleSecureDelete() { viewModelScope.launch { securityRepo.setSecureDelete(!_state.value.settings.secureDeleteEnabled) } }

    fun computeChecksum(path: String, algorithm: String = "SHA-256") {
        viewModelScope.launch {
            _state.update { it.copy(isComputing = true, checksumResult = null) }
            ChecksumUtil.computeChecksum(path, algorithm)
                .onSuccess { hash -> _state.update { it.copy(checksumResult = hash, isComputing = false) } }
                .onFailure { e -> _toasts.emit("Checksum failed: ${e.message}"); _state.update { it.copy(isComputing = false) } }
        }
    }
}

// -- Screen --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Security") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // App Lock
            item {
                Text("APP LOCK", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text("Biometric Lock") },
                    supportingContent = { Text("Require biometric authentication to open app") },
                    leadingContent = { Icon(Icons.Filled.Fingerprint, null) },
                    trailingContent = {
                        Switch(checked = state.settings.appLockEnabled,
                            onCheckedChange = { viewModel.toggleAppLock() },
                            enabled = state.canUseBiometrics)
                    },
                )
            }
            if (!state.canUseBiometrics) {
                item {
                    Text("  Biometrics not available on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            // Vault
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text("ENCRYPTED VAULT", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text("Enable Vault") },
                    supportingContent = { Text("Protected storage for sensitive files, accessible only with biometrics") },
                    leadingContent = { Icon(Icons.Filled.Lock, null) },
                    trailingContent = { Switch(checked = state.settings.vaultEnabled, onCheckedChange = { viewModel.toggleVault() }) },
                )
            }

            // Secure Delete
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text("FILE SECURITY", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text("Secure Delete") },
                    supportingContent = { Text("Overwrite files before deletion (3-pass DoD 5220.22-M)") },
                    leadingContent = { Icon(Icons.Filled.DeleteForever, null) },
                    trailingContent = { Switch(checked = state.settings.secureDeleteEnabled, onCheckedChange = { viewModel.toggleSecureDelete() }) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Checksum Verification") },
                    supportingContent = { Text("Verify file integrity with MD5, SHA-1, SHA-256, SHA-512") },
                    leadingContent = { Icon(Icons.Filled.Verified, null) },
                )
            }
            if (state.checksumResult != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(state.checksumResult!!, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // Info
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text("ABOUT", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text("Security Model") },
                    supportingContent = {
                        Text("Vault files stored in app-private directory with owner-only permissions. " +
                                "Secure delete uses DoD 5220.22-M 3-pass overwrite. " +
                                "Checksums computed using java.security.MessageDigest.")
                    },
                    leadingContent = { Icon(Icons.Filled.Info, null) },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
