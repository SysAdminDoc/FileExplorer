package com.explorer.fileexplorer.feature.security

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.explorer.fileexplorer.core.data.EncryptedVolumeFormat
import com.explorer.fileexplorer.core.data.EncryptedVolumeManager
import com.explorer.fileexplorer.core.data.EncryptedVolumeMount
import com.explorer.fileexplorer.core.data.EncryptedVolumeRequest
import com.explorer.fileexplorer.core.database.IntegrityEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
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

// SecureDelete is in core:data — com.explorer.fileexplorer.core.data.SecureDelete

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
    private companion object {
        const val KEYSTORE_ALIAS = "file_explorer_vault"
        const val IV_SIZE = 12
        const val BUFFER_SIZE = 65536
    }

    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        ks.getKey(KEYSTORE_ALIAS, null)?.let { return it as javax.crypto.SecretKey }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val keygen = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keygen.init(spec)
        return keygen.generateKey()
    }

    fun getVaultPath(): String {
        vaultDir.mkdirs()
        vaultDir.setReadable(false, false)
        vaultDir.setReadable(true, true)
        vaultDir.setWritable(false, false)
        vaultDir.setWritable(true, true)
        vaultDir.setExecutable(false, false)
        vaultDir.setExecutable(true, true)
        return vaultDir.absolutePath
    }

    suspend fun lockFile(sourcePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val source = File(sourcePath)
            if (!source.exists()) return@withContext Result.failure(Exception("Source not found"))
            val encName = source.name + ".enc"
            var dest = File(getVaultPath(), encName)
            if (dest.exists()) {
                var counter = 1
                while (dest.exists()) {
                    dest = File(getVaultPath(), "${source.nameWithoutExtension} ($counter).${source.extension}.enc")
                    counter++
                }
            }
            val key = getOrCreateKey()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            dest.outputStream().buffered().use { out ->
                out.write(iv)
                source.inputStream().buffered().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        out.write(cipher.update(buf, 0, len) ?: ByteArray(0))
                    }
                    out.write(cipher.doFinal())
                }
            }
            source.delete()
            Result.success(dest.absolutePath)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unlockFile(vaultPath: String, destinationDir: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val source = File(vaultPath)
            if (!source.exists()) return@withContext Result.failure(Exception("Vault file not found"))
            val originalName = if (source.name.endsWith(".enc")) source.name.removeSuffix(".enc") else source.name
            val dest = File(destinationDir, originalName)
            val key = getOrCreateKey()
            source.inputStream().buffered().use { input ->
                val iv = ByteArray(IV_SIZE)
                var read = 0
                while (read < IV_SIZE) {
                    val n = input.read(iv, read, IV_SIZE - read)
                    if (n < 0) return@withContext Result.failure(Exception("Truncated vault file"))
                    read += n
                }
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                dest.outputStream().buffered().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        out.write(cipher.update(buf, 0, len) ?: ByteArray(0))
                    }
                    out.write(cipher.doFinal())
                }
            }
            source.delete()
            Result.success(dest.absolutePath)
        } catch (e: Exception) { Result.failure(e) }
    }

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
    val integrityEntries: List<IntegrityEntryEntity> = emptyList(),
    val isScanningIntegrity: Boolean = false,
    val encryptedFormats: List<EncryptedVolumeFormat> = emptyList(),
    val encryptedMounts: List<EncryptedVolumeMount> = emptyList(),
    val isLoadingEncryptedVolumes: Boolean = false,
    val isMountingEncryptedVolume: Boolean = false,
    val showEncryptedVolumeDialog: Boolean = false,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val securityRepo: SecurityRepository,
    private val vaultManager: VaultManager,
    private val integrityRepository: IntegrityRepository,
    private val encryptedVolumeManager: EncryptedVolumeManager,
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
        viewModelScope.launch {
            integrityRepository.entries.collect { entries ->
                _state.update { it.copy(integrityEntries = entries) }
            }
        }
        refreshEncryptedVolumes()
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

    fun addIntegrityPath(path: String) {
        viewModelScope.launch {
            integrityRepository.addPath(path)
                .onSuccess { _toasts.emit("Watching ${it.path}") }
                .onFailure { error -> _toasts.emit("Watch failed: ${error.message}") }
        }
    }

    fun removeIntegrityPath(path: String) {
        viewModelScope.launch {
            integrityRepository.removePath(path)
            _toasts.emit("Removed integrity watch")
        }
    }

    fun scanIntegrityNow() {
        if (_state.value.isScanningIntegrity) return
        viewModelScope.launch {
            _state.update { it.copy(isScanningIntegrity = true) }
            integrityRepository.scanNow()
                .onSuccess { summary ->
                    _toasts.emit(
                        "Integrity scan: ${summary.checked} checked, " +
                            "${summary.changed} changed, ${summary.missing} missing",
                    )
                }
                .onFailure { error -> _toasts.emit("Integrity scan failed: ${error.message}") }
            _state.update { it.copy(isScanningIntegrity = false) }
        }
    }

    fun refreshEncryptedVolumes() {
        if (_state.value.isLoadingEncryptedVolumes) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingEncryptedVolumes = true) }
            runCatching {
                encryptedVolumeManager.detectFormats() to encryptedVolumeManager.listMounted()
            }.onSuccess { (formats, mounts) ->
                _state.update {
                    it.copy(
                        encryptedFormats = formats,
                        encryptedMounts = mounts,
                        isLoadingEncryptedVolumes = false,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoadingEncryptedVolumes = false) }
                _toasts.emit("Encrypted volume refresh failed: ${error.message}")
            }
        }
    }

    fun showEncryptedVolumeDialog() {
        _state.update { it.copy(showEncryptedVolumeDialog = true) }
    }

    fun dismissEncryptedVolumeDialog() {
        _state.update { it.copy(showEncryptedVolumeDialog = false) }
    }

    fun mountEncryptedVolume(
        format: EncryptedVolumeFormat,
        cipherPath: String,
        mountPath: String,
        readOnly: Boolean,
        passphrase: CharArray,
    ) {
        if (_state.value.isMountingEncryptedVolume) {
            passphrase.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    showEncryptedVolumeDialog = false,
                    isMountingEncryptedVolume = true,
                )
            }
            encryptedVolumeManager.mount(
                EncryptedVolumeRequest(format, cipherPath, mountPath, readOnly),
                passphrase,
            ).onSuccess {
                _toasts.emit("${format.label} volume mounted")
            }.onFailure { error ->
                _toasts.emit("Encrypted volume mount failed: ${error.message}")
            }
            _state.update { it.copy(isMountingEncryptedVolume = false) }
            refreshEncryptedVolumes()
        }
    }

    fun unmountEncryptedVolume(mountPath: String) {
        viewModelScope.launch {
            encryptedVolumeManager.unmount(mountPath)
                .onSuccess { _toasts.emit("Encrypted volume unmounted") }
                .onFailure { error -> _toasts.emit("Encrypted volume unmount failed: ${error.message}") }
            refreshEncryptedVolumes()
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
    var showIntegrityPathDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    if (showIntegrityPathDialog) {
        IntegrityPathDialog(
            onConfirm = { path ->
                showIntegrityPathDialog = false
                viewModel.addIntegrityPath(path)
            },
            onDismiss = { showIntegrityPathDialog = false },
        )
    }
    if (state.showEncryptedVolumeDialog) {
        EncryptedVolumeDialog(
            formats = state.encryptedFormats,
            isMounting = state.isMountingEncryptedVolume,
            onConfirm = { format, cipherPath, mountPath, readOnly, passphrase ->
                viewModel.mountEncryptedVolume(format, cipherPath, mountPath, readOnly, passphrase)
            },
            onDismiss = viewModel::dismissEncryptedVolumeDialog,
        )
    }

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
            item {
                ListItem(
                    headlineContent = { Text("File Encryption") },
                    supportingContent = {
                        Text("Select files in Browser, open More, then Encrypt files. Decryption requires biometric authentication.")
                    },
                    leadingContent = { Icon(Icons.Filled.EnhancedEncryption, null) },
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

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("Integrity Watch") },
                    supportingContent = {
                        Text("Track SHA-256 fingerprints and alert when watched paths change or disappear")
                    },
                    leadingContent = { Icon(Icons.Filled.VerifiedUser, null) },
                    trailingContent = {
                        IconButton(onClick = { showIntegrityPathDialog = true }) {
                            Icon(Icons.Filled.Add, "Watch path")
                        }
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showIntegrityPathDialog = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("Add path") }
                    Button(
                        onClick = viewModel::scanIntegrityNow,
                        enabled = state.integrityEntries.isNotEmpty() && !state.isScanningIntegrity,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.isScanningIntegrity) "Scanning..." else "Scan now") }
                }
            }
            if (state.integrityEntries.isEmpty()) {
                item {
                    Text(
                        "No watched paths. Add a path here or select files in the browser and choose Watch for changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(state.integrityEntries, key = { it.path }) { entry ->
                    IntegrityEntryRow(entry = entry, onRemove = { viewModel.removeIntegrityPath(entry.path) })
                }
            }

            // Encrypted volumes
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text("ENCRYPTED VOLUMES", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text("Encrypted volumes") },
                    supportingContent = {
                        Text("Mount existing gocryptfs or EncFS volumes through the root environment")
                    },
                    leadingContent = { Icon(Icons.Filled.EnhancedEncryption, null) },
                    trailingContent = {
                        IconButton(
                            onClick = viewModel::refreshEncryptedVolumes,
                            enabled = !state.isLoadingEncryptedVolumes && !state.isMountingEncryptedVolume,
                        ) { Icon(Icons.Filled.Refresh, "Refresh encrypted volumes") }
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::showEncryptedVolumeDialog,
                        enabled = state.encryptedFormats.isNotEmpty() && !state.isMountingEncryptedVolume,
                        modifier = Modifier.weight(1f),
                    ) { Text("Mount volume") }
                }
            }
            if (state.isLoadingEncryptedVolumes) {
                item {
                    Text(
                        "Checking root environment...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (state.encryptedFormats.isEmpty()) {
                item {
                    Text(
                        "Root access and a compatible gocryptfs or EncFS binary with FUSE support are required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (state.encryptedMounts.isEmpty()) {
                item {
                    Text(
                        "No encrypted volumes mounted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(state.encryptedMounts, key = { it.mountPath }) { mount ->
                    EncryptedVolumeMountRow(
                        mount = mount,
                        onUnmount = { viewModel.unmountEncryptedVolume(mount.mountPath) },
                    )
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
                        Text("Vault files are AES-256-GCM encrypted with Android Keystore-backed keys. " +
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

@Composable
private fun EncryptedVolumeMountRow(mount: EncryptedVolumeMount, onUnmount: () -> Unit) {
    ListItem(
        headlineContent = { Text("${mount.format.label}: ${mount.mountPath}", maxLines = 1) },
        supportingContent = {
            Text(
                "Cipher: ${mount.cipherPath} · ${if (mount.readOnly) "read-only" else "read/write"}",
                maxLines = 2,
            )
        },
        leadingContent = { Icon(Icons.Filled.Lock, null) },
        trailingContent = {
            IconButton(onClick = onUnmount) { Icon(Icons.Filled.Eject, "Unmount volume") }
        },
    )
}

@Composable
private fun EncryptedVolumeDialog(
    formats: List<EncryptedVolumeFormat>,
    isMounting: Boolean,
    onConfirm: (EncryptedVolumeFormat, String, String, Boolean, CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedFormat by remember(formats) { mutableStateOf(formats.firstOrNull()) }
    var cipherPath by remember { mutableStateOf("") }
    var mountPath by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(false) }
    val canSubmit = selectedFormat != null && cipherPath.isNotBlank() &&
        mountPath.isNotBlank() && passphrase.isNotEmpty() && !isMounting

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mount encrypted volume") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    formats.forEach { format ->
                        FilterChip(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            label = { Text(format.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = cipherPath,
                    onValueChange = { cipherPath = it },
                    label = { Text("Cipher directory") },
                    supportingText = { Text("Absolute path to an existing encrypted volume") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mountPath,
                    onValueChange = { mountPath = it },
                    label = { Text("Empty mount directory") },
                    supportingText = { Text("Files already in this directory are rejected") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Read-only mount")
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
                Text(
                    "Only existing volumes are supported. The passphrase is sent through a temporary protected file and is not included in the root command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val format = selectedFormat ?: return@TextButton
                    val secret = passphrase.toCharArray()
                    passphrase = ""
                    onConfirm(format, cipherPath.trim(), mountPath.trim(), readOnly, secret)
                },
                enabled = canSubmit,
            ) { Text(if (isMounting) "Mounting..." else "Mount") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isMounting) { Text("Cancel") } },
    )
}

@Composable
private fun IntegrityEntryRow(entry: IntegrityEntryEntity, onRemove: () -> Unit) {
    val statusColor = when (entry.status) {
        IntegrityStatuses.OK -> MaterialTheme.colorScheme.primary
        IntegrityStatuses.CHANGED, IntegrityStatuses.MISSING -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        headlineContent = { Text(entry.path, maxLines = 1) },
        supportingContent = {
            Text(
                buildString {
                    append(entry.status)
                    entry.lastCheckedAt?.let { append(" · checked ${java.util.Date(it)}") }
                    entry.lastError?.let { append(" · $it") }
                },
                color = statusColor,
                maxLines = 2,
            )
        },
        leadingContent = { Icon(Icons.Filled.Fingerprint, null, tint = statusColor) },
        trailingContent = {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove watch") }
        },
    )
}

@Composable
private fun IntegrityPathDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch path") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Absolute file or directory path") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (path.isNotBlank()) onConfirm(path.trim()) }) { Text("Watch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
