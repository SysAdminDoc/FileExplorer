package com.explorer.fileexplorer.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.math.ceil

const val HEX_PAGE_SIZE = 4096
const val HEX_BYTES_PER_LINE = 16
const val HEX_MAX_EDITABLE_BYTES = 64L * 1024L * 1024L

data class HexLine(
    val offset: Long,
    val hex: String,
    val ascii: String,
)

/** Pure formatting and parsing helpers kept separate from file I/O for JVM coverage. */
object HexDumpFormatter {
    fun format(bytes: ByteArray, startOffset: Long = 0L): List<HexLine> = buildList {
        for (offset in bytes.indices step HEX_BYTES_PER_LINE) {
            val count = minOf(HEX_BYTES_PER_LINE, bytes.size - offset)
            val hex = buildString {
                repeat(HEX_BYTES_PER_LINE) { index ->
                    if (index > 0) append(' ')
                    if (index < count) {
                        append((bytes[offset + index].toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
                    } else {
                        append("  ")
                    }
                }
            }
            val ascii = buildString {
                repeat(count) { index ->
                    val value = bytes[offset + index].toInt() and 0xff
                    append(if (value in 0x20..0x7e) value.toChar() else '.')
                }
            }
            add(HexLine(startOffset + offset, hex, ascii))
        }
    }

    fun parseHexBytes(value: String): ByteArray? {
        val tokens = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return ByteArray(0)
        return tokens.map { token ->
            if (token.length != 2 || token.any { character -> character.digitToIntOrNull(16) == null }) {
                return null
            }
            token.toInt(16).toByte()
        }.toByteArray()
    }
}

data class HexEditorUiState(
    val filePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val pageOffset: Long = 0L,
    val pageBytes: ByteArray = ByteArray(0),
    val lines: List<HexLine> = emptyList(),
    val isLoading: Boolean = true,
    val isModified: Boolean = false,
    val isReadOnly: Boolean = false,
    val error: String? = null,
) {
    val canGoPrevious: Boolean get() = pageIndex > 0 && !isModified
    val canGoNext: Boolean get() = pageIndex < pageCount - 1 && !isModified
}

@HiltViewModel
class HexEditorViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(HexEditorUiState())
    val state: StateFlow<HexEditorUiState> = _state.asStateFlow()

    fun loadFile(path: String) {
        if (path == _state.value.filePath && !_state.value.isLoading) return
        viewModelScope.launch {
            _state.value = HexEditorUiState(
                filePath = path,
                fileName = path.substringAfterLast('/'),
            )
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    require(file.isFile) { "Only regular files can be opened in the hex editor" }
                    val size = file.length()
                    val pageCount = maxOf(1, ceil(size / HEX_PAGE_SIZE.toDouble()).toInt())
                    val bytes = readPage(file, 0L)
                    _state.update {
                        it.copy(
                            fileSize = size,
                            pageCount = pageCount,
                            pageBytes = bytes,
                            lines = HexDumpFormatter.format(bytes),
                            isLoading = false,
                            isReadOnly = size > HEX_MAX_EDITABLE_BYTES,
                            error = null,
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message ?: "Unable to read file") }
                }
            }
        }
    }

    fun previousPage() = loadPage(_state.value.pageIndex - 1)

    fun nextPage() = loadPage(_state.value.pageIndex + 1)

    fun updateLine(lineIndex: Int, value: String): Boolean {
        val current = _state.value
        if (current.isReadOnly || lineIndex !in current.lines.indices) return false
        val parsed = HexDumpFormatter.parseHexBytes(value) ?: return false
        val lineStart = lineIndex * HEX_BYTES_PER_LINE
        val expected = minOf(HEX_BYTES_PER_LINE, current.pageBytes.size - lineStart)
        if (parsed.size != expected) return false
        val updated = current.pageBytes.copyOf()
        parsed.copyInto(updated, destinationOffset = lineStart)
        _state.update {
            it.copy(
                pageBytes = updated,
                lines = HexDumpFormatter.format(updated, it.pageOffset),
                isModified = true,
            )
        }
        return true
    }

    fun save() {
        val current = _state.value
        if (current.isReadOnly || !current.isModified || current.filePath.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    RandomAccessFile(current.filePath, "rw").use { file ->
                        file.seek(current.pageOffset)
                        file.write(current.pageBytes)
                    }
                }.onSuccess {
                    _state.update { it.copy(isModified = false) }
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Unable to save file") }
                }
            }
        }
    }

    private fun loadPage(index: Int) {
        val current = _state.value
        if (index !in 0 until current.pageCount || current.isModified) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            withContext(Dispatchers.IO) {
                runCatching {
                    val offset = index.toLong() * HEX_PAGE_SIZE
                    val bytes = readPage(File(current.filePath), offset)
                    _state.update {
                        it.copy(
                            pageIndex = index,
                            pageOffset = offset,
                            pageBytes = bytes,
                            lines = HexDumpFormatter.format(bytes, offset),
                            isLoading = false,
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message ?: "Unable to read page") }
                }
            }
        }
    }

    private fun readPage(file: File, offset: Long): ByteArray {
        RandomAccessFile(file, "r").use { randomAccessFile ->
            randomAccessFile.seek(offset)
            val remaining = (randomAccessFile.length() - offset).coerceAtLeast(0L)
            val buffer = ByteArray(minOf(HEX_PAGE_SIZE.toLong(), remaining).toInt())
            var read = 0
            while (read < buffer.size) {
                val count = randomAccessFile.read(buffer, read, buffer.size - read)
                if (count <= 0) break
                read += count
            }
            return if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexEditorScreen(
    filePath: String,
    onNavigateBack: () -> Unit = {},
    viewModel: HexEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingLine by remember { mutableStateOf<HexLine?>(null) }

    LaunchedEffect(filePath) { viewModel.loadFile(filePath) }
    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isModified) "${state.fileName} *" else state.fileName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isModified) {
                        IconButton(onClick = viewModel::save) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(32.dp))
            } else {
                Text(
                    text = "${formatOffset(state.pageOffset)}  •  ${state.fileSize} bytes  •  page ${state.pageIndex + 1}/${state.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.isReadOnly) {
                    Text(
                        text = "Files larger than 64 MiB are read-only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(state.lines, key = { _, line -> line.offset }) { index, line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isReadOnly) { editingLine = line }
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(formatOffset(line.offset), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(line.hex, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(line.ascii, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = viewModel::previousPage, enabled = state.canGoPrevious) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
                        Text("Previous")
                    }
                    Text("${state.pageBytes.size} bytes", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = viewModel::nextPage, enabled = state.canGoNext) {
                        Text("Next")
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
                    }
                }
            }
        }
    }

    editingLine?.let { line ->
        var hexValue by remember(line.offset) { mutableStateOf(line.hex.trim()) }
        AlertDialog(
            onDismissRequest = { editingLine = null },
            title = { Text("Edit bytes at ${formatOffset(line.offset)}") },
            text = {
                OutlinedTextField(
                    value = hexValue,
                    onValueChange = { hexValue = it },
                    label = { Text("Hex bytes") },
                    supportingText = { Text("Use two hexadecimal digits per byte") },
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val lineIndex = state.lines.indexOfFirst { it.offset == line.offset }
                    if (lineIndex >= 0 && viewModel.updateLine(lineIndex, hexValue)) editingLine = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editingLine = null }) { Text("Cancel") } },
        )
    }
}

private fun formatOffset(offset: Long): String = offset.toString(16).uppercase().padStart(8, '0')
