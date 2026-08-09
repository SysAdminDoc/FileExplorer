package com.explorer.fileexplorer.feature.security

import android.content.Context
import android.os.Environment
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
import androidx.compose.ui.res.stringResource
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
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
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
    private val indexFile: File get() = File(vaultDir, INDEX_NAME)

    private companion object {
        const val KEYSTORE_ALIAS = "file_explorer_vault_v2"
        const val INDEX_NAME = "index.v1"
        const val TEMP_PREFIX = ".fileexplorer-vault-"
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

    /**
     * Opens a vault session. The caller must invoke this only after the configured
     * biometric/device-credential gate has completed successfully.
     */
    suspend fun unlock(): Result<VaultSession> = withContext(Dispatchers.IO) {
        runCatching {
            ensureVaultDirectory()
            val key = getOrCreateKey()
            readRecords(key)
            VaultSession(key)
        }
    }

    suspend fun listVaultEntries(session: VaultSession): Result<List<VaultEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            session.requireUnlocked()
            readRecords(session.key).map { record ->
                VaultEntry(record.id, record.originalName, record.size, record.modifiedAt)
            }
        }
    }

    suspend fun addToVault(sourcePath: String, session: VaultSession): Result<VaultEntry> = withContext(Dispatchers.IO) {
        runCatching {
            session.requireUnlocked()
            ensureVaultDirectory()
            val source = File(sourcePath)
            require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Only regular local files can enter the vault"
            }
            VaultFilePolicy.validateName(source.name)
            val records = readRecords(session.key)
            val record = VaultIndexRecord(
                id = UUID.randomUUID().toString(),
                originalName = source.name,
                size = source.length(),
                modifiedAt = source.lastModified().coerceAtLeast(0),
            )
            val temporary = createTemporaryFile()
            val destination = payloadFile(record.id)
            var moved = false
            try {
                VaultPayloadFormat.encrypt(source, temporary, session.key)
                moveIntoPlace(temporary, destination)
                moved = true
                writeRecords(records + record, session.key)
                try {
                    require(Files.deleteIfExists(source.toPath())) { "Unable to remove plaintext source" }
                } catch (error: Exception) {
                    runCatching { writeRecords(records, session.key) }
                    runCatching { Files.deleteIfExists(destination.toPath()) }
                    throw error
                }
                VaultEntry(record.id, record.originalName, record.size, record.modifiedAt)
            } catch (error: Exception) {
                if (moved) runCatching { Files.deleteIfExists(destination.toPath()) }
                throw error
            } finally {
                runCatching { Files.deleteIfExists(temporary.toPath()) }
            }
        }
    }

    suspend fun restoreFile(
        entryId: String,
        destinationDir: String,
        session: VaultSession,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            session.requireUnlocked()
            val records = readRecords(session.key)
            val record = records.firstOrNull { it.id == entryId }
                ?: error("Vault entry not found")
            val directory = File(destinationDir).canonicalFile
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Restore destination is not a directory"
            }
            val destination = File(directory, record.originalName)
            require(destination.parentFile?.canonicalFile == directory) { "Invalid restore destination" }
            require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Restore destination already exists"
            }
            val payload = payloadFile(record.id)
            require(Files.isRegularFile(payload.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Vault payload is missing"
            }
            val temporary = File(directory, TEMP_PREFIX + UUID.randomUUID() + ".tmp")
            Files.createFile(temporary.toPath())
            var moved = false
            try {
                VaultPayloadFormat.decrypt(payload, temporary, session.key)
                writeRecords(records - record, session.key)
                try {
                    moveIntoPlace(temporary, destination)
                    moved = true
                    require(Files.deleteIfExists(payload.toPath())) { "Unable to remove restored vault payload" }
                } catch (error: Exception) {
                    if (moved) runCatching { Files.deleteIfExists(destination.toPath()) }
                    runCatching { writeRecords(records, session.key) }
                    throw error
                }
                destination.absolutePath
            } finally {
                runCatching { Files.deleteIfExists(temporary.toPath()) }
            }
        }
    }

    suspend fun deleteFromVault(entryId: String, session: VaultSession): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            session.requireUnlocked()
            val records = readRecords(session.key)
            val record = records.firstOrNull { it.id == entryId }
                ?: error("Vault entry not found")
            val payload = payloadFile(record.id)
            require(Files.isRegularFile(payload.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Vault payload is missing"
            }
            writeRecords(records - record, session.key)
            try {
                require(Files.deleteIfExists(payload.toPath())) { "Unable to delete vault payload" }
            } catch (error: Exception) {
                runCatching { writeRecords(records, session.key) }
                throw error
            }
        }
    }

    fun lock(session: VaultSession?) {
        session?.lock()
    }

    private fun ensureVaultDirectory() {
        if (Files.exists(vaultDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(vaultDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Vault storage is not a directory"
            }
        } else {
            Files.createDirectories(vaultDir.toPath())
        }
    }

    private fun readRecords(key: javax.crypto.SecretKey): List<VaultIndexRecord> {
        ensureVaultDirectory()
        val files = vaultDir.listFiles().orEmpty()
        val unexpected = files.filter { file ->
            file.name != INDEX_NAME && !file.name.startsWith(TEMP_PREFIX) &&
                !file.name.endsWith(VaultFilePolicy.PAYLOAD_SUFFIX)
        }
        require(unexpected.isEmpty()) { "Unrecognized vault storage entry" }
        if (!Files.exists(indexFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(files.none { it.name.endsWith(VaultFilePolicy.PAYLOAD_SUFFIX) }) {
                "Vault index is missing"
            }
            return emptyList()
        }
        require(Files.isRegularFile(indexFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Vault index is not a regular file"
        }
        val records = VaultIndexCodec.decode(indexFile.readBytes(), key)
        require(records.map { it.id }.toSet().size == records.size) { "Duplicate vault entry id" }
        val payloadNames = records.mapTo(hashSetOf()) { VaultFilePolicy.payloadName(it.id) }
        val actualPayloads = files.filter { it.name.endsWith(VaultFilePolicy.PAYLOAD_SUFFIX) }
        require(actualPayloads.all { it.name in payloadNames }) { "Untracked vault payload" }
        require(payloadNames.all { expected -> actualPayloads.any { it.name == expected } }) {
            "Vault payload is missing"
        }
        actualPayloads.forEach { payload ->
            require(Files.isRegularFile(payload.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Vault payload is not a regular file"
            }
        }
        return records
    }

    private fun writeRecords(records: List<VaultIndexRecord>, key: javax.crypto.SecretKey) {
        val temporary = File(vaultDir, TEMP_PREFIX + UUID.randomUUID() + ".index.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(VaultIndexCodec.encode(records, key))
                output.fd.sync()
            }
            moveIntoPlace(temporary, indexFile)
        } finally {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
        }
    }

    private fun payloadFile(id: String): File {
        val file = File(vaultDir, VaultFilePolicy.payloadName(id))
        require(file.parentFile?.canonicalFile == vaultDir.canonicalFile) { "Invalid vault payload path" }
        return file
    }

    private fun createTemporaryFile(): File {
        val file = File(vaultDir, TEMP_PREFIX + UUID.randomUUID() + ".tmp")
        Files.createFile(file.toPath())
        return file
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

// -- ViewModel --

data class SecurityUiState(
    val settings: SecuritySettings = SecuritySettings(),
    val canUseBiometrics: Boolean = false,
    val vaultEntries: List<VaultEntry> = emptyList(),
    val vaultUnlocked: Boolean = false,
    val isVaultBusy: Boolean = false,
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

    private var vaultSession: VaultSession? = null

    init {
        _state.update { it.copy(canUseBiometrics = securityRepo.canUseBiometrics()) }
        viewModelScope.launch {
            securityRepo.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
                if (!settings.vaultEnabled) lockVault()
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
    fun toggleVault() {
        val enabled = !_state.value.settings.vaultEnabled
        if (enabled && !_state.value.canUseBiometrics) {
            viewModelScope.launch { _toasts.emit("Vault requires biometric or device-credential authentication") }
            return
        }
        viewModelScope.launch {
            if (!enabled) lockVault()
            securityRepo.setVaultEnabled(enabled)
        }
    }
    fun toggleSecureDelete() { viewModelScope.launch { securityRepo.setSecureDelete(!_state.value.settings.secureDeleteEnabled) } }

    /** Call only from a UI callback after biometric/device-credential success. */
    fun unlockVault() {
        if (_state.value.isVaultBusy || !_state.value.settings.vaultEnabled) return
        viewModelScope.launch {
            _state.update { it.copy(isVaultBusy = true) }
            vaultManager.unlock()
                .onSuccess { session ->
                    vaultManager.listVaultEntries(session)
                        .onSuccess { entries ->
                            vaultManager.lock(vaultSession)
                            vaultSession = session
                            _state.update {
                                it.copy(
                                    vaultEntries = entries,
                                    vaultUnlocked = true,
                                    isVaultBusy = false,
                                )
                            }
                        }
                        .onFailure { error ->
                            vaultManager.lock(session)
                            _state.update { it.copy(isVaultBusy = false) }
                            _toasts.emit("Vault unlock failed: ${error.message}")
                        }
                }
                .onFailure { error ->
                    _state.update { it.copy(isVaultBusy = false) }
                    _toasts.emit("Vault unlock failed: ${error.message}")
                }
        }
    }

    fun lockVault() {
        vaultManager.lock(vaultSession)
        vaultSession = null
        _state.update { it.copy(vaultEntries = emptyList(), vaultUnlocked = false, isVaultBusy = false) }
    }

    fun restoreVaultEntry(entry: VaultEntry) {
        val session = vaultSession ?: run {
            viewModelScope.launch { _toasts.emit("Unlock the vault first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isVaultBusy = true) }
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            vaultManager.restoreFile(entry.id, downloads.absolutePath, session)
                .onSuccess { restoredPath ->
                    _toasts.emit("Restored to Downloads: ${File(restoredPath).name}")
                    refreshVaultEntries(session)
                }
                .onFailure { error -> _toasts.emit("Restore failed: ${error.message}") }
            _state.update { it.copy(isVaultBusy = false) }
        }
    }

    fun deleteVaultEntry(entry: VaultEntry) {
        val session = vaultSession ?: run {
            viewModelScope.launch { _toasts.emit("Unlock the vault first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isVaultBusy = true) }
            vaultManager.deleteFromVault(entry.id, session)
                .onSuccess { _toasts.emit("Deleted ${entry.displayName} from vault") }
                .onFailure { error -> _toasts.emit("Vault deletion failed: ${error.message}") }
            refreshVaultEntries(session)
            _state.update { it.copy(isVaultBusy = false) }
        }
    }

    private suspend fun refreshVaultEntries(session: VaultSession) {
        vaultManager.listVaultEntries(session)
            .onSuccess { entries -> _state.update { it.copy(vaultEntries = entries) } }
            .onFailure { error ->
                vaultManager.lock(session)
                if (vaultSession === session) vaultSession = null
                _state.update { it.copy(vaultEntries = emptyList(), vaultUnlocked = false) }
                _toasts.emit("Vault refresh failed: ${error.message}")
            }
    }

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

    override fun onCleared() {
        vaultManager.lock(vaultSession)
        vaultSession = null
        super.onCleared()
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
    val securityEntryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SecurityEntryPoint::class.java,
        )
    }
    var showIntegrityPathDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    DisposableEffect(Unit) { onDispose { viewModel.lockVault() } }

    fun authenticateVault(title: String, subtitle: String, onSuccess: () -> Unit) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            Toast.makeText(context, "Biometric authentication unavailable", Toast.LENGTH_SHORT).show()
        } else {
            securityEntryPoint.biometricHelper().showBiometricPrompt(
                activity = activity,
                title = title,
                subtitle = subtitle,
                onSuccess = onSuccess,
                onFailure = { reason ->
                    Toast.makeText(context, "Vault authentication cancelled: $reason", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

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
            TopAppBar(title = { Text(stringResource(DesignSystemR.string.security)) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back)) } })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // App Lock
            item {
                Text(stringResource(DesignSystemR.string.app_lock).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.biometric_lock)) },
                    supportingContent = { Text(stringResource(DesignSystemR.string.require_biometric)) },
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
                    Text("  ${stringResource(DesignSystemR.string.biometrics_unavailable)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            // Vault
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text(stringResource(DesignSystemR.string.encrypted_vault).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.enable_vault)) },
                    supportingContent = { Text(stringResource(DesignSystemR.string.vault_description)) },
                    leadingContent = { Icon(Icons.Filled.Lock, null) },
                    trailingContent = {
                        Switch(
                            checked = state.settings.vaultEnabled,
                            onCheckedChange = { viewModel.toggleVault() },
                            enabled = state.canUseBiometrics,
                        )
                    },
                )
            }
            if (state.settings.vaultEnabled) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.vaultUnlocked) {
                            OutlinedButton(
                                onClick = viewModel::lockVault,
                                enabled = !state.isVaultBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Lock, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(DesignSystemR.string.vault_lock))
                            }
                        } else {
                            Button(
                                onClick = {
                                    authenticateVault(
                                        title = "Unlock vault",
                                        subtitle = "Authenticate to view protected files",
                                        onSuccess = viewModel::unlockVault,
                                    )
                                },
                                enabled = !state.isVaultBusy && state.canUseBiometrics,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.LockOpen, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(DesignSystemR.string.vault_unlock))
                            }
                        }
                    }
                }
                if (state.vaultUnlocked && state.vaultEntries.isEmpty()) {
                    item {
                        Text(
                            stringResource(DesignSystemR.string.vault_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (state.vaultUnlocked) {
                    items(state.vaultEntries, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.displayName) },
                            supportingContent = {
                                Text(stringResource(DesignSystemR.string.vault_entry_size, entry.size))
                            },
                            leadingContent = { Icon(Icons.Filled.Lock, null) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { viewModel.restoreVaultEntry(entry) },
                                        enabled = !state.isVaultBusy,
                                    ) { Text(stringResource(DesignSystemR.string.vault_restore)) }
                                    IconButton(
                                        onClick = { viewModel.deleteVaultEntry(entry) },
                                        enabled = !state.isVaultBusy,
                                    ) { Icon(Icons.Filled.DeleteForever, stringResource(DesignSystemR.string.vault_delete)) }
                                }
                            },
                        )
                    }
                }
            }

            // Secure Delete
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text(stringResource(DesignSystemR.string.file_security).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.secure_delete)) },
                    supportingContent = { Text(stringResource(DesignSystemR.string.secure_delete_description)) },
                    leadingContent = { Icon(Icons.Filled.DeleteForever, null) },
                    trailingContent = { Switch(checked = state.settings.secureDeleteEnabled, onCheckedChange = { viewModel.toggleSecureDelete() }) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.checksum_verification)) },
                    supportingContent = { Text(stringResource(DesignSystemR.string.checksum_description)) },
                    leadingContent = { Icon(Icons.Filled.Verified, null) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.file_encryption)) },
                    supportingContent = {
                        Text(stringResource(DesignSystemR.string.file_encryption_description))
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
                    headlineContent = { Text(stringResource(DesignSystemR.string.integrity_watch)) },
                    supportingContent = {
                        Text(stringResource(DesignSystemR.string.integrity_watch_description))
                    },
                    leadingContent = { Icon(Icons.Filled.VerifiedUser, null) },
                    trailingContent = {
                        IconButton(onClick = { showIntegrityPathDialog = true }) {
                            Icon(Icons.Filled.Add, stringResource(DesignSystemR.string.watch_path))
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
                    ) { Text(stringResource(DesignSystemR.string.add_path)) }
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
                Text(stringResource(DesignSystemR.string.encrypted_volumes).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.encrypted_volumes)) },
                    supportingContent = {
                        Text(stringResource(DesignSystemR.string.encrypted_volumes_description))
                    },
                    leadingContent = { Icon(Icons.Filled.EnhancedEncryption, null) },
                    trailingContent = {
                        IconButton(
                            onClick = viewModel::refreshEncryptedVolumes,
                            enabled = !state.isLoadingEncryptedVolumes && !state.isMountingEncryptedVolume,
                        ) { Icon(Icons.Filled.Refresh, stringResource(DesignSystemR.string.refresh)) }
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
                    ) { Text(stringResource(DesignSystemR.string.mount_volume)) }
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
                Text(stringResource(DesignSystemR.string.about).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(DesignSystemR.string.security_model)) },
                    supportingContent = {
                        Text(stringResource(DesignSystemR.string.security_model_description) + " " +
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
        headlineContent = { Text(stringResource(DesignSystemR.string.volume_title, mount.format.label, mount.mountPath), maxLines = 1) },
        supportingContent = {
            Text(
                stringResource(
                    DesignSystemR.string.volume_details,
                    mount.cipherPath,
                    if (mount.readOnly) stringResource(DesignSystemR.string.read_only) else stringResource(DesignSystemR.string.read_write),
                ),
                maxLines = 2,
            )
        },
        leadingContent = { Icon(Icons.Filled.Lock, null) },
        trailingContent = {
            IconButton(onClick = onUnmount) { Icon(Icons.Filled.Eject, stringResource(DesignSystemR.string.unmount_volume)) }
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
        title = { Text(stringResource(DesignSystemR.string.mount_encrypted_volume)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(DesignSystemR.string.format), style = MaterialTheme.typography.labelLarge)
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
                    label = { Text(stringResource(DesignSystemR.string.cipher_directory)) },
                    supportingText = { Text(stringResource(DesignSystemR.string.cipher_directory_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mountPath,
                    onValueChange = { mountPath = it },
                    label = { Text(stringResource(DesignSystemR.string.empty_mount_directory)) },
                    supportingText = { Text(stringResource(DesignSystemR.string.empty_mount_directory_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(DesignSystemR.string.passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(DesignSystemR.string.read_only_mount))
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
                Text(
                    stringResource(DesignSystemR.string.encrypted_volume_mount_note),
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
            ) { Text(if (isMounting) stringResource(DesignSystemR.string.mounting) else stringResource(DesignSystemR.string.mount_volume)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isMounting) { Text(stringResource(DesignSystemR.string.cancel)) } },
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
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(DesignSystemR.string.remove_watch)) }
        },
    )
}

@Composable
private fun IntegrityPathDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignSystemR.string.watch_path)) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(DesignSystemR.string.absolute_file_or_directory_path)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (path.isNotBlank()) onConfirm(path.trim()) }) { Text(stringResource(DesignSystemR.string.watch)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignSystemR.string.cancel)) } },
    )
}
