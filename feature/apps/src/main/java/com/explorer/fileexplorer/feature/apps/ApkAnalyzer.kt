package com.explorer.fileexplorer.feature.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

data class ApkPermission(
    val name: String,
    val protection: String,
)

data class ApkSignature(
    val sha256: String,
    val subject: String,
)

data class ApkDirectoryStat(
    val directory: String,
    val entryCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
)

data class ApkAnalysis(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sharedUserId: String?,
    val permissions: List<ApkPermission>,
    val signatures: List<ApkSignature>,
    val directories: List<ApkDirectoryStat>,
    val dexFileCount: Int,
    val dexMethodCount: Int,
)

data class ApkAnalyzerUiState(
    val isLoading: Boolean = true,
    val analysis: ApkAnalysis? = null,
    val error: String? = null,
)

class ApkAnalyzer(private val context: Context) {
    fun analyze(path: String): ApkAnalysis {
        val packageManager = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = packageManager.getPackageArchiveInfo(path, flags)
            ?: error("Unable to read APK manifest")
        val permissions = packageInfo.requestedPermissions.orEmpty().map { permission ->
            ApkPermission(permission, permissionProtection(packageManager, permission))
        }
        return ZipFile(path).use { zip ->
            val directoryStats = mutableMapOf<String, MutableDirectoryStat>()
            var dexFileCount = 0
            var dexMethodCount = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val directory = entry.name.trimStart('/').substringBefore('/').ifBlank { "[root]" }
                val stat = directoryStats.getOrPut(directory) { MutableDirectoryStat() }
                stat.entryCount++
                stat.uncompressedBytes += entry.size.coerceAtLeast(0L)
                stat.compressedBytes += entry.compressedSize.coerceAtLeast(0L)
                if (entry.name.matches(DEX_ENTRY_PATTERN)) {
                    dexFileCount++
                    zip.getInputStream(entry).use { input ->
                        dexMethodCount += DexHeaderReader.methodCount(input) ?: 0
                    }
                }
            }
            ApkAnalysis(
                packageName = packageInfo.packageName,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
                sharedUserId = packageInfo.sharedUserId,
                permissions = permissions,
                signatures = signatures(packageInfo),
                directories = directoryStats.map { (name, stat) ->
                    ApkDirectoryStat(name, stat.entryCount, stat.uncompressedBytes, stat.compressedBytes)
                }.sortedByDescending { it.uncompressedBytes },
                dexFileCount = dexFileCount,
                dexMethodCount = dexMethodCount,
            )
        }
    }

    private fun permissionProtection(packageManager: PackageManager, name: String): String {
        val level = runCatching { packageManager.getPermissionInfo(name, 0).protectionLevel and 0xf }
            .getOrDefault(-1)
        return when (level) {
            0 -> "normal"
            1 -> "dangerous"
            2 -> "signature"
            3 -> "signatureOrSystem"
            else -> "unknown"
        }
    }

    private fun signatures(packageInfo: android.content.pm.PackageInfo): List<ApkSignature> {
        val signingCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.toList().orEmpty()
        }
        return signingCertificates.map { signature ->
            ApkSignature(
                sha256 = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHexString(),
                subject = signature.toCharsString(),
            )
        }
    }

    private class MutableDirectoryStat(
        var entryCount: Int = 0,
        var uncompressedBytes: Long = 0L,
        var compressedBytes: Long = 0L,
    )

    private companion object {
        val DEX_ENTRY_PATTERN = Regex("classes(\\d*)?\\.dex")
    }
}

object DexHeaderReader {
    private const val METHOD_IDS_SIZE_OFFSET = 88
    private const val HEADER_BYTES = METHOD_IDS_SIZE_OFFSET + 4

    fun methodCount(input: InputStream): Int? {
        val header = ByteArray(HEADER_BYTES)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read <= 0) return null
            offset += read
        }
        return methodCount(header)
    }

    fun methodCount(header: ByteArray): Int? {
        if (header.size < HEADER_BYTES || header[0] != 'd'.code.toByte() || header[1] != 'e'.code.toByte() || header[2] != 'x'.code.toByte()) {
            return null
        }
        val value = (header[METHOD_IDS_SIZE_OFFSET].toInt() and 0xff) or
            ((header[METHOD_IDS_SIZE_OFFSET + 1].toInt() and 0xff) shl 8) or
            ((header[METHOD_IDS_SIZE_OFFSET + 2].toInt() and 0xff) shl 16) or
            ((header[METHOD_IDS_SIZE_OFFSET + 3].toInt() and 0xff) shl 24)
        return value.takeIf { it >= 0 }
    }
}

@HiltViewModel
class ApkAnalyzerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(ApkAnalyzerUiState())
    val state: StateFlow<ApkAnalyzerUiState> = _state.asStateFlow()

    fun analyze(path: String) {
        if (_state.value.analysis != null && !_state.value.isLoading) return
        viewModelScope.launch {
            _state.value = ApkAnalyzerUiState(isLoading = true)
            withContext(Dispatchers.IO) {
                runCatching { ApkAnalyzer(context).analyze(path) }
                    .onSuccess { result -> _state.value = ApkAnalyzerUiState(isLoading = false, analysis = result) }
                    .onFailure { error -> _state.value = ApkAnalyzerUiState(isLoading = false, error = error.message ?: "Unable to analyze APK") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkAnalyzerSheet(
    app: AppInfo,
    onDismiss: () -> Unit,
    viewModel: ApkAnalyzerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(app.apkPath) { viewModel.analyze(app.apkPath) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("APK analyzer", style = MaterialTheme.typography.headlineSmall)
            Text(app.name, style = MaterialTheme.typography.titleMedium)
            Text(app.apkPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when {
                state.isLoading -> CircularProgressIndicator()
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
                state.analysis != null -> ApkAnalysisContent(state.analysis!!)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ApkAnalysisContent(analysis: ApkAnalysis) {
    AnalyzerSection("Manifest") {
        AnalyzerRow("Package", analysis.packageName)
        AnalyzerRow("Version", "${analysis.versionName} (${analysis.versionCode})")
        AnalyzerRow("Shared UID", analysis.sharedUserId ?: "None")
    }
    AnalyzerSection("DEX") {
        AnalyzerRow("DEX files", analysis.dexFileCount.toString())
        AnalyzerRow("Method IDs", analysis.dexMethodCount.toString())
    }
    AnalyzerSection("Signatures") {
        if (analysis.signatures.isEmpty()) {
            Text("No signing certificate found", style = MaterialTheme.typography.bodySmall)
        } else {
            analysis.signatures.forEach { signature ->
                AnalyzerRow("SHA-256", signature.sha256)
                AnalyzerRow("Certificate", signature.subject)
            }
        }
    }
    AnalyzerSection("Requested permissions (${analysis.permissions.size})") {
        analysis.permissions.forEach { permission ->
            AnalyzerRow(permission.protection, permission.name)
        }
    }
    AnalyzerSection("APK size by directory") {
        analysis.directories.forEach { directory ->
            AnalyzerRow(
                directory.directory,
                "${formatApkSize(directory.uncompressedBytes)} uncompressed · ${directory.entryCount} entries",
            )
        }
    }
}

@Composable
private fun AnalyzerSection(title: String, content: @Composable () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
}

@Composable
private fun AnalyzerRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.65f))
    }
}

private fun formatApkSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
