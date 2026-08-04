package com.explorer.fileexplorer.feature.browser

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.ui.FileListItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: CollectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selected = state.selectedCategory

    BackHandler(enabled = selected != null) { viewModel.closeCategory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.title ?: "Collections") },
                navigationIcon = {
                    IconButton(onClick = if (selected == null) onNavigateBack else viewModel::closeCategory) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (state.isLoading && state.files.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(32.dp))
            }
            if (selected == null) {
                CollectionSummaryList(
                    summaries = state.summaries,
                    onOpen = viewModel::open,
                )
            } else {
                CollectionFiles(
                    files = state.files,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun CollectionSummaryList(
    summaries: List<CollectionSummary>,
    onOpen: (CollectionCategory) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(summaries, key = { it.category.name }) { summary ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(summary.category) },
            ) {
                ListItem(
                    headlineContent = { Text(summary.category.title) },
                    supportingContent = {
                        Text("${summary.itemCount} items · ${formatCollectionBytes(summary.totalBytes)}")
                    },
                    leadingContent = {
                        Icon(collectionIcon(summary.category), contentDescription = null, modifier = Modifier.size(32.dp))
                    },
                    trailingContent = { Text("Open", color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

@Composable
private fun CollectionFiles(
    files: List<FileItem>,
    context: Context,
) {
    if (files.isEmpty()) {
        Text(
            text = "No indexed files in this collection",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(24.dp),
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(files, key = { it.path }) { item ->
                FileListItem(
                    item = item,
                    onClick = { openCollectionFile(context, item) },
                    onLongClick = {},
                )
            }
        }
    }
}

private fun openCollectionFile(context: Context, item: FileItem) {
    runCatching {
        val uri = item.uri ?: FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            File(item.path),
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun collectionIcon(category: CollectionCategory) = when (category) {
    CollectionCategory.PHOTOS -> Icons.Filled.Image
    CollectionCategory.VIDEOS -> Icons.Filled.Movie
    CollectionCategory.MUSIC -> Icons.Filled.AudioFile
    CollectionCategory.DOCUMENTS -> Icons.Filled.Description
    CollectionCategory.DOWNLOADS -> Icons.Filled.Download
    CollectionCategory.APKS -> Icons.Filled.Android
}

private fun formatCollectionBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
