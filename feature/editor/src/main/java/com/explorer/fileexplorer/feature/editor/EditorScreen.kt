package com.explorer.fileexplorer.feature.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject

data class EditorUiState(
    val filePath: String = "",
    val fileName: String = "",
    val content: String = "",
    val originalContent: String = "",
    val isLoading: Boolean = true,
    val isModified: Boolean = false,
    val encoding: Charset = Charsets.UTF_8,
    val lineCount: Int = 1,
    val cursorLine: Int = 1,
    val cursorCol: Int = 1,
    val fileSize: Long = 0L,
    val showFindBar: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val findMatches: Int = 0,
    val wordWrap: Boolean = true,
    val showLineNumbers: Boolean = true,
    val readOnly: Boolean = false,
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList(),
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val title: String get() = if (isModified) "$fileName *" else fileName
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>()
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    fun loadFile(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, filePath = path, fileName = path.substringAfterLast('/')) }
            withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    val size = file.length()
                    // Detect encoding — simple BOM check
                    val bytes = file.readBytes()
                    val encoding = detectEncoding(bytes)
                    val text = String(bytes, encoding)
                    val lines = text.count { it == '\n' } + 1
                    val readOnly = !file.canWrite() || size > 5 * 1024 * 1024 // Read-only for >5MB
                    _state.update {
                        it.copy(content = text, originalContent = text, isLoading = false,
                            encoding = encoding, lineCount = lines, fileSize = size,
                            readOnly = readOnly, undoStack = emptyList(), redoStack = emptyList())
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(isLoading = false) }
                    _toasts.emit("Error loading file: ${e.message}")
                }
            }
        }
    }

    fun updateContent(newContent: String) {
        val current = _state.value.content
        if (newContent == current) return
        val lines = newContent.count { it == '\n' } + 1
        _state.update {
            it.copy(
                content = newContent,
                isModified = newContent != it.originalContent,
                lineCount = lines,
                undoStack = it.undoStack + current,
                redoStack = emptyList(),
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    File(_state.value.filePath).writeText(_state.value.content, _state.value.encoding)
                    _state.update { it.copy(originalContent = it.content, isModified = false) }
                    _toasts.emit("Saved")
                } catch (e: Exception) {
                    _toasts.emit("Save failed: ${e.message}")
                }
            }
        }
    }

    fun undo() {
        val stack = _state.value.undoStack
        if (stack.isEmpty()) return
        val previous = stack.last()
        _state.update {
            it.copy(
                content = previous,
                isModified = previous != it.originalContent,
                lineCount = previous.count { c -> c == '\n' } + 1,
                undoStack = stack.dropLast(1),
                redoStack = it.redoStack + it.content,
            )
        }
    }

    fun redo() {
        val stack = _state.value.redoStack
        if (stack.isEmpty()) return
        val next = stack.last()
        _state.update {
            it.copy(
                content = next,
                isModified = next != it.originalContent,
                lineCount = next.count { c -> c == '\n' } + 1,
                undoStack = it.undoStack + it.content,
                redoStack = stack.dropLast(1),
            )
        }
    }

    fun toggleFind() { _state.update { it.copy(showFindBar = !it.showFindBar) } }
    fun setFindQuery(q: String) {
        val matches = if (q.isNotEmpty()) _state.value.content.windowed(q.length) { it }.count { it.toString() == q } else 0
        _state.update { it.copy(findQuery = q, findMatches = matches) }
    }
    fun setReplaceQuery(q: String) { _state.update { it.copy(replaceQuery = q) } }

    fun replaceAll() {
        val find = _state.value.findQuery
        val replace = _state.value.replaceQuery
        if (find.isEmpty()) return
        updateContent(_state.value.content.replace(find, replace))
    }

    fun toggleWordWrap() { _state.update { it.copy(wordWrap = !it.wordWrap) } }
    fun toggleLineNumbers() { _state.update { it.copy(showLineNumbers = !it.showLineNumbers) } }

    private fun detectEncoding(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        return Charsets.UTF_8
    }
}

// -- Syntax highlighting --

private val keywordColor = Color(0xFF569CD6)    // Blue
private val stringColor = Color(0xFFCE9178)     // Orange
private val commentColor = Color(0xFF6A9955)    // Green
private val numberColor = Color(0xFFB5CEA8)     // Light green
private val punctuationColor = Color(0xFFD4D4D4)

private val keywords = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for",
    "while", "do", "return", "break", "continue", "import", "package", "private", "public",
    "protected", "internal", "override", "abstract", "data", "sealed", "companion",
    "suspend", "inline", "const", "lateinit", "by", "lazy", "this", "super", "null",
    "true", "false", "is", "as", "in", "try", "catch", "finally", "throw",
    // Common across languages
    "function", "def", "let", "const", "var", "int", "string", "bool", "void",
    "static", "final", "new", "delete", "typeof", "instanceof", "extends", "implements",
)

@Composable
fun syntaxHighlight(line: String, extension: String) = buildAnnotatedString {
    if (extension !in setOf("kt", "java", "py", "js", "ts", "c", "cpp", "rs", "go", "sh", "ps1", "xml", "html", "json")) {
        append(line)
        return@buildAnnotatedString
    }

    var i = 0
    while (i < line.length) {
        when {
            // Line comment
            i < line.length - 1 && line[i] == '/' && line[i + 1] == '/' -> {
                withStyle(SpanStyle(color = commentColor)) { append(line.substring(i)) }
                return@buildAnnotatedString
            }
            line[i] == '#' && extension in setOf("py", "sh", "ps1") -> {
                withStyle(SpanStyle(color = commentColor)) { append(line.substring(i)) }
                return@buildAnnotatedString
            }
            // String
            line[i] == '"' || line[i] == '\'' -> {
                val quote = line[i]
                val start = i; i++
                while (i < line.length && line[i] != quote) { if (line[i] == '\\') i++; i++ }
                if (i < line.length) i++
                withStyle(SpanStyle(color = stringColor)) { append(line.substring(start, i)) }
            }
            // Number
            line[i].isDigit() -> {
                val start = i
                while (i < line.length && (line[i].isDigit() || line[i] == '.' || line[i] == 'x' || line[i] == 'f' || line[i] == 'L')) i++
                withStyle(SpanStyle(color = numberColor)) { append(line.substring(start, i)) }
            }
            // Identifier / keyword
            line[i].isLetter() || line[i] == '_' -> {
                val start = i
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                val word = line.substring(start, i)
                if (word in keywords) {
                    withStyle(SpanStyle(color = keywordColor)) { append(word) }
                } else { append(word) }
            }
            else -> { append(line[i]); i++ }
        }
    }
}

// -- Editor Screen --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    filePath: String,
    viewModel: EditorViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(filePath) { viewModel.loadFile(filePath) }
    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text("${state.encoding.name()} | ${state.lineCount} lines | ${formatSize(state.fileSize)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!state.readOnly) {
                        IconButton(onClick = viewModel::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                        IconButton(onClick = viewModel::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                        IconButton(onClick = viewModel::save, enabled = state.isModified) { Icon(Icons.Filled.Save, "Save") }
                    }
                    IconButton(onClick = viewModel::toggleFind) { Icon(Icons.Filled.Search, "Find") }
                    var moreExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        DropdownMenuItem(text = { Text(if (state.wordWrap) "Disable word wrap" else "Enable word wrap") },
                            onClick = { viewModel.toggleWordWrap(); moreExpanded = false })
                        DropdownMenuItem(text = { Text(if (state.showLineNumbers) "Hide line numbers" else "Show line numbers") },
                            onClick = { viewModel.toggleLineNumbers(); moreExpanded = false })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Find/Replace bar
            if (state.showFindBar) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = state.findQuery, onValueChange = viewModel::setFindQuery,
                                label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                            Spacer(Modifier.width(8.dp))
                            Text("${state.findMatches} matches", style = MaterialTheme.typography.labelSmall)
                        }
                        if (!state.readOnly) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                OutlinedTextField(value = state.replaceQuery, onValueChange = viewModel::setReplaceQuery,
                                    label = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                                Spacer(Modifier.width(8.dp))
                                FilledTonalButton(onClick = viewModel::replaceAll) { Text("All") }
                            }
                        }
                    }
                }
            }

            // Read-only banner
            if (state.readOnly) {
                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                    Text("Read-only", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // Editor content
                val lines = state.content.split('\n')
                val listState = rememberLazyListState()
                val ext = state.filePath.substringAfterLast('.', "").lowercase()

                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    // Line numbers
                    if (state.showLineNumbers) {
                        LazyColumn(state = listState, modifier = Modifier.width(48.dp).padding(end = 4.dp)) {
                            itemsIndexed(lines) { index, _ ->
                                Text("${index + 1}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.padding(end = 8.dp).fillMaxWidth(), maxLines = 1)
                            }
                        }
                        VerticalDivider(modifier = Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // Content area
                    if (state.readOnly) {
                        // Read-only: syntax highlighted view
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
                            .let { if (!state.wordWrap) it.horizontalScroll(rememberScrollState()) else it }
                            .padding(horizontal = 8.dp)) {
                            itemsIndexed(lines) { _, line ->
                                Text(syntaxHighlight(line, ext),
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface),
                                    softWrap = state.wordWrap)
                            }
                        }
                    } else {
                        // Editable text field
                        BasicTextField(
                            value = state.content,
                            onValueChange = viewModel::updateContent,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxSize()
                                .let { if (!state.wordWrap) it.horizontalScroll(rememberScrollState()) else it }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
