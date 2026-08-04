package com.explorer.fileexplorer.feature.browser

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.explorer.fileexplorer.core.model.FileItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class CollectionCategory(
    val title: String,
    val description: String,
) {
    PHOTOS("Photos", "Images from the media library"),
    VIDEOS("Videos", "Movies and recorded clips"),
    MUSIC("Music", "Songs and audio tracks"),
    DOCUMENTS("Documents", "Text, PDF, office, and data files"),
    DOWNLOADS("Downloads", "Files in the shared Download folder"),
    APKS("APKs", "Android application packages"),
}

data class CollectionSummary(
    val category: CollectionCategory,
    val itemCount: Int = 0,
    val totalBytes: Long = 0L,
)

data class CollectionsUiState(
    val summaries: List<CollectionSummary> = CollectionCategory.entries.map(::CollectionSummary),
    val selectedCategory: CollectionCategory? = null,
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val resolver = context.contentResolver
    private val _state = MutableStateFlow(CollectionsUiState())
    val state: StateFlow<CollectionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val selected = _state.value.selectedCategory
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val summaries = CollectionCategory.entries.map { category -> querySummary(category) }
                    val files = selected?.let(::queryFiles).orEmpty()
                    summaries to files
                }
            }.onSuccess { (summaries, files) ->
                _state.update {
                    it.copy(summaries = summaries, files = files, isLoading = false)
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(isLoading = false, error = error.message ?: "Unable to read media storage")
                }
            }
        }
    }

    fun open(category: CollectionCategory) {
        viewModelScope.launch {
            _state.update { it.copy(selectedCategory = category, files = emptyList(), isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { queryFiles(category) } }
                .onSuccess { files -> _state.update { it.copy(files = files, isLoading = false) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, error = error.message ?: "Unable to read ${category.title.lowercase()}")
                    }
                }
        }
    }

    fun closeCategory() {
        _state.update { it.copy(selectedCategory = null, files = emptyList(), error = null) }
    }

    private fun querySummary(category: CollectionCategory): CollectionSummary {
        val spec = querySpec(category)
        var totalBytes = 0L
        var itemCount = 0
        resolver.query(
            spec.uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            spec.selection,
            spec.args,
            null,
        )?.use { cursor ->
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                itemCount++
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    totalBytes += cursor.getLong(sizeColumn).coerceAtLeast(0L)
                }
            }
        }
        return CollectionSummary(category, itemCount, totalBytes)
    }

    private fun queryFiles(category: CollectionCategory): List<FileItem> {
        val spec = querySpec(category)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
        )
        val items = mutableListOf<FileItem>()
        resolver.query(spec.uri, projection, spec.selection, spec.args, SORT_ORDER)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = nameColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString).orEmpty()
                val path = pathColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString).orEmpty()
                val contentUri = ContentUris.withAppendedId(spec.uri, id)
                val resolvedPath = path.ifBlank { contentUri.toString() }
                val resolvedName = name.ifBlank { resolvedPath.substringAfterLast('/') }
                val mimeType = mimeColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        resolvedName.substringAfterLast('.', "").lowercase(),
                    )
                    ?: "application/octet-stream"
                items += FileItem(
                    name = resolvedName,
                    path = resolvedPath,
                    absolutePath = resolvedPath,
                    uri = contentUri,
                    size = sizeColumn.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: 0L,
                    lastModified = modifiedColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)?.times(1000L) ?: 0L,
                    isHidden = resolvedName.startsWith('.'),
                    isReadable = true,
                    isWritable = true,
                    mimeType = mimeType,
                    extension = resolvedName.substringAfterLast('.', ""),
                )
            }
        }
        return items
    }

    private fun querySpec(category: CollectionCategory): QuerySpec = when (category) {
        CollectionCategory.PHOTOS -> QuerySpec(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        CollectionCategory.VIDEOS -> QuerySpec(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        CollectionCategory.MUSIC -> QuerySpec(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        )
        CollectionCategory.DOCUMENTS -> QuerySpec(
            FILES_URI,
            selection = "(${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ${MediaStore.MediaColumns.MIME_TYPE} IN (${DOCUMENT_MIME_TYPES.joinToString { "?" }}))",
            args = arrayOf("text/%", *DOCUMENT_MIME_TYPES),
        )
        CollectionCategory.DOWNLOADS -> QuerySpec(
            FILES_URI,
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?",
            args = arrayOf("%/Download/%"),
        )
        CollectionCategory.APKS -> QuerySpec(
            FILES_URI,
            selection = "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
            args = arrayOf("application/vnd.android.package-archive", "%.apk"),
        )
    }

    private data class QuerySpec(
        val uri: android.net.Uri,
        val selection: String? = null,
        val args: Array<String>? = null,
    )

    private companion object {
        val FILES_URI = MediaStore.Files.getContentUri("external")
        val DOCUMENT_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/zip",
        )
        const val SORT_ORDER = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
    }
}
