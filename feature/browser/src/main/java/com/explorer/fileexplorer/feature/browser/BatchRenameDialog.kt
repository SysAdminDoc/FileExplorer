package com.explorer.fileexplorer.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.data.BatchRenameEngine
import com.explorer.fileexplorer.core.data.BatchRenameOptions
import com.explorer.fileexplorer.core.data.BatchRenamePreviewItem
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import com.explorer.fileexplorer.core.model.FileItem

@Composable
fun BatchRenameDialog(
    items: List<FileItem>,
    onConfirm: (BatchRenameOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var template by remember { mutableStateOf("{name}_{counter}{ext}") }
    var regex by remember { mutableStateOf("") }
    var counterStart by remember { mutableStateOf("1") }
    var counterPadding by remember { mutableStateOf("2") }
    var datePattern by remember { mutableStateOf("yyyy-MM-dd") }

    val start = counterStart.toIntOrNull()
    val padding = counterPadding.toIntOrNull()
    val options = remember(template, regex, start, padding, datePattern) {
        BatchRenameOptions(
            template = template,
            regex = regex,
            counterStart = start ?: 1,
            counterPadding = padding ?: 2,
            datePattern = datePattern,
        )
    }
    val preview = remember(items, options) { BatchRenameEngine.preview(items, options) }
    val numberError = start == null || padding == null || padding < 0
    val message = when {
        numberError -> "Counter values must be whole numbers; padding cannot be negative."
        preview.errors.isNotEmpty() -> preview.errors.joinToString("; ")
        !preview.isValid -> "The template does not change any selected names."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignSystemR.string.batch_rename)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(DesignSystemR.string.batch_rename_template)) },
                    supportingText = { Text(stringResource(DesignSystemR.string.batch_rename_tokens)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = regex,
                    onValueChange = { regex = it },
                    label = { Text(stringResource(DesignSystemR.string.optional_regex_pattern)) },
                    supportingText = { Text(stringResource(DesignSystemR.string.capture_groups_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = counterStart,
                        onValueChange = { counterStart = it },
                        label = { Text(stringResource(DesignSystemR.string.start)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = counterPadding,
                        onValueChange = { counterPadding = it },
                        label = { Text(stringResource(DesignSystemR.string.padding)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = datePattern,
                        onValueChange = { datePattern = it },
                        label = { Text(stringResource(DesignSystemR.string.date_format)) },
                        singleLine = true,
                        modifier = Modifier.weight(1.5f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(DesignSystemR.string.live_preview_count, items.size), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(preview.items, key = { it.item.path }) { item ->
                        BatchRenamePreviewRow(item)
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(options) },
                enabled = preview.isValid && !numberError,
            ) { Text(stringResource(DesignSystemR.string.rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignSystemR.string.cancel)) } },
    )
}

@Composable
private fun BatchRenamePreviewRow(item: BatchRenamePreviewItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (item.error == null) {
            Text(
                text = "${item.item.name}  →  ${item.newName}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = "${item.item.name}: ${item.error}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
