package com.explorer.fileexplorer.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

data class DocumentPreviewUiState(
    val filePath: String = "",
    val fileName: String = "",
    val type: PreviewDocumentType? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val pdfPage: Int = 0,
    val pdfPageCount: Int = 0,
    val pdfBitmap: Bitmap? = null,
    val document: DocumentPreviewData? = null,
)

private data class PdfRenderResult(
    val pageCount: Int,
    val bitmap: Bitmap,
)

@HiltViewModel
class DocumentPreviewViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(DocumentPreviewUiState())
    val state: StateFlow<DocumentPreviewUiState> = _state.asStateFlow()

    private var operation: Job? = null

    fun loadFile(path: String) {
        if (path == _state.value.filePath && !_state.value.isLoading) return
        operation?.cancel()
        recycle(_state.value.pdfBitmap)
        val type = when (File(path).extension.lowercase()) {
            "pdf" -> PreviewDocumentType.PDF
            "docx" -> PreviewDocumentType.DOCX
            "xlsx" -> PreviewDocumentType.XLSX
            "epub" -> PreviewDocumentType.EPUB
            else -> null
        }
        _state.value = DocumentPreviewUiState(
            filePath = path,
            fileName = path.substringAfterLast('/'),
            type = type,
            isLoading = true,
        )
        operation = viewModelScope.launch {
            var rendered: PdfRenderResult? = null
            try {
                when (type) {
                    PreviewDocumentType.PDF -> {
                        rendered = withContext(Dispatchers.IO) { renderPdfPage(path, 0) }
                        _state.value = _state.value.copy(
                            isLoading = false,
                            pdfPageCount = rendered!!.pageCount,
                            pdfBitmap = rendered!!.bitmap,
                        )
                        rendered = null
                    }

                    PreviewDocumentType.DOCX, PreviewDocumentType.XLSX, PreviewDocumentType.EPUB -> {
                        val document = withContext(Dispatchers.IO) { DocumentPreviewParser.parse(path) }
                        _state.value = _state.value.copy(isLoading = false, document = document)
                    }

                    null -> error("Unsupported preview format")
                }
            } catch (_: CancellationException) {
                recycle(rendered?.bitmap)
                throw CancellationException()
            } catch (error: Exception) {
                recycle(rendered?.bitmap)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = error.message ?: "Unable to preview file",
                )
            }
        }
    }

    fun previousPage() {
        val current = _state.value
        if (current.type != PreviewDocumentType.PDF || current.pdfPage == 0 || current.isLoading) return
        renderPage(current.pdfPage - 1)
    }

    fun nextPage() {
        val current = _state.value
        if (current.type != PreviewDocumentType.PDF || current.pdfPage + 1 >= current.pdfPageCount || current.isLoading) return
        renderPage(current.pdfPage + 1)
    }

    private fun renderPage(page: Int) {
        val current = _state.value
        operation?.cancel()
        _state.value = current.copy(isLoading = true, error = null)
        operation = viewModelScope.launch {
            var rendered: PdfRenderResult? = null
            try {
                rendered = withContext(Dispatchers.IO) { renderPdfPage(current.filePath, page) }
                recycle(_state.value.pdfBitmap)
                _state.value = _state.value.copy(
                    isLoading = false,
                    pdfPage = page,
                    pdfPageCount = rendered!!.pageCount,
                    pdfBitmap = rendered!!.bitmap,
                )
                rendered = null
            } catch (_: CancellationException) {
                recycle(rendered?.bitmap)
                throw CancellationException()
            } catch (error: Exception) {
                recycle(rendered?.bitmap)
                _state.value = _state.value.copy(isLoading = false, error = error.message ?: "Unable to render PDF page")
            }
        }
    }

    private fun renderPdfPage(path: String, pageIndex: Int): PdfRenderResult {
        val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        try {
            require(pageIndex in 0 until renderer.pageCount) { "PDF page is out of range" }
            val page = renderer.openPage(pageIndex)
            var bitmap: Bitmap? = null
            try {
                val scale = minOf(
                    2.5f,
                    1440f / page.width.toFloat(),
                    4096f / page.height.toFloat(),
                )
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return PdfRenderResult(renderer.pageCount, bitmap)
            } catch (error: Exception) {
                recycle(bitmap)
                throw error
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    override fun onCleared() {
        operation?.cancel()
        recycle(_state.value.pdfBitmap)
        super.onCleared()
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPreviewScreen(
    filePath: String,
    viewModel: DocumentPreviewViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(filePath) { viewModel.loadFile(filePath) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.fileName.ifBlank { "Preview" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(DesignSystemR.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.type == PreviewDocumentType.PDF -> PdfPreviewContent(state, viewModel)
                state.type == PreviewDocumentType.DOCX -> DocxPreviewContent(state.document?.paragraphs.orEmpty())
                state.type == PreviewDocumentType.XLSX -> XlsxPreviewContent(state.document?.rows.orEmpty())
                state.type == PreviewDocumentType.EPUB -> EpubPreviewContent(state.document)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPreviewContent(state: DocumentPreviewUiState, viewModel: DocumentPreviewViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = viewModel::previousPage, enabled = state.pdfPage > 0) {
                Icon(Icons.Filled.ChevronLeft, stringResource(DesignSystemR.string.previous_page))
            }
            Text(stringResource(DesignSystemR.string.pdf_page_count, state.pdfPage + 1, state.pdfPageCount), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = viewModel::nextPage, enabled = state.pdfPage + 1 < state.pdfPageCount) {
                Icon(Icons.Filled.ChevronRight, stringResource(DesignSystemR.string.next_page))
            }
        }
        HorizontalDivider()
        state.pdfBitmap?.let { bitmap ->
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(DesignSystemR.string.pdf_page, state.pdfPage + 1),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun DocxPreviewContent(paragraphs: List<String>) {
    if (paragraphs.isEmpty()) {
        EmptyPreviewMessage(stringResource(DesignSystemR.string.docx_no_text))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(paragraphs) { _, paragraph ->
            Text(paragraph, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XlsxPreviewContent(rows: List<List<String>>) {
    if (rows.isEmpty()) {
        EmptyPreviewMessage(stringResource(DesignSystemR.string.xlsx_no_cells))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(rows) { index, row ->
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                )
                row.forEach { value ->
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(160.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EpubPreviewContent(document: DocumentPreviewData?) {
    val chapters = document?.epubChapters.orEmpty()
    if (chapters.isEmpty()) {
        EmptyPreviewMessage(stringResource(DesignSystemR.string.epub_no_text))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        document?.title?.takeIf(String::isNotBlank)?.let { title ->
            item {
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
        }
        chapters.forEach { chapter ->
            item {
                Text(chapter.title, style = MaterialTheme.typography.titleLarge)
            }
            items(chapter.paragraphs) { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun EmptyPreviewMessage(message: String) {
    Text(message, modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
