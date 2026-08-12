package com.explorer.fileexplorer.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.model.FileColumn
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: FileItem,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    visibleColumns: Set<FileColumn> = FileColumn.DEFAULT_VISIBLE_COLUMNS,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surface,
        label = "grid_selection_bg",
    )

    Surface(color = backgroundColor, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClickLabel = stringResource(DesignSystemR.string.open),
                    onLongClickLabel = stringResource(DesignSystemR.string.select),
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (selectionMode && isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(DesignSystemR.string.select),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    FileIcon(item = item, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = if (item.isHidden) Modifier.alpha(0.6f) else Modifier,
            )
            val metadata = buildList {
                if (FileColumn.SIZE in visibleColumns) {
                    if (item.isDirectory) {
                        item.childCount?.let { count ->
                            add(pluralStringResource(DesignSystemR.plurals.items_count, count, count))
                        }
                    } else {
                        add(item.displaySize)
                    }
                }
                if (FileColumn.TYPE in visibleColumns) {
                    add(if (item.isDirectory) stringResource(DesignSystemR.string.folder) else item.extension.ifBlank { item.mimeType })
                }
                if (FileColumn.DATE in visibleColumns && item.lastModified > 0) add(formatGridDate(item.lastModified))
            }
            if (metadata.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = metadata.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun formatGridDate(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> stringResource(DesignSystemR.string.just_now)
        diff < 3_600_000 -> pluralStringResource(
            DesignSystemR.plurals.minutes_ago,
            (diff / 60_000).toInt(),
            diff / 60_000,
        )
        diff < 86_400_000 -> pluralStringResource(
            DesignSystemR.plurals.hours_ago,
            (diff / 3_600_000).toInt(),
            diff / 3_600_000,
        )
        else -> pluralStringResource(
            DesignSystemR.plurals.days_ago,
            (diff / 86_400_000).toInt(),
            diff / 86_400_000,
        )
    }
}
