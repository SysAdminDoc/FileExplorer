package com.explorer.fileexplorer.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.explorer.fileexplorer.core.model.FileColumn
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import java.text.DateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    item: FileItem,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    compact: Boolean = false,
    visibleColumns: Set<FileColumn> = FileColumn.DEFAULT_VISIBLE_COLUMNS,
    showDirectorySizes: Boolean = false,
    directorySize: Long? = null,
    directorySizeUnavailable: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val swipeModifier = if (onSwipeLeft != null || onSwipeRight != null) {
        Modifier.pointerInput(swipeThreshold, onSwipeLeft, onSwipeRight) {
            var dragDistance = 0f
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount ->
                    dragDistance += amount
                    change.consume()
                },
                onDragEnd = {
                    when {
                        dragDistance <= -swipeThreshold -> onSwipeLeft?.invoke()
                        dragDistance >= swipeThreshold -> onSwipeRight?.invoke()
                    }
                    dragDistance = 0f
                },
                onDragCancel = { dragDistance = 0f },
            )
        }
    } else {
        Modifier
    }
    val swipeLeftLabel = stringResource(DesignSystemR.string.swipe_left)
    val swipeRightLabel = stringResource(DesignSystemR.string.swipe_right)

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surface,
        label = "selection_bg",
    )

    Surface(
        color = backgroundColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .then(swipeModifier)
                .combinedClickable(
                    onClickLabel = stringResource(DesignSystemR.string.open),
                    onLongClickLabel = stringResource(DesignSystemR.string.select),
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .semantics {
                    customActions = buildList {
                        onSwipeLeft?.let { action ->
                            add(CustomAccessibilityAction(swipeLeftLabel) { action(); true })
                        }
                        onSwipeRight?.let { action ->
                            add(CustomAccessibilityAction(swipeRightLabel) { action(); true })
                        }
                    }
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection indicator or file icon
            Box(modifier = Modifier.size(if (compact) 32.dp else 40.dp), contentAlignment = Alignment.Center) {
                if (selectionMode && isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(DesignSystemR.string.select),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    FileIcon(item = item, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name and metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (item.isHidden) Modifier.alpha(0.6f) else Modifier,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (FileColumn.SIZE in visibleColumns) {
                        if (item.isDirectory) {
                            item.childCount?.let { count ->
                                Text(
                                    text = pluralStringResource(DesignSystemR.plurals.items_count, count, count),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showDirectorySizes) {
                                Text(
                                    text = when {
                                        directorySize != null -> FileItem.formatSize(directorySize)
                                        directorySizeUnavailable -> stringResource(DesignSystemR.string.directory_size_unavailable)
                                        else -> stringResource(DesignSystemR.string.directory_size_calculating)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                text = item.displaySize,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (FileColumn.DATE in visibleColumns && item.lastModified > 0) {
                        Text(
                            text = formatDate(item.lastModified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (FileColumn.TYPE in visibleColumns) {
                        Text(
                            text = if (item.isDirectory) stringResource(DesignSystemR.string.folder)
                            else item.extension.ifBlank { item.mimeType },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

            // Symlink indicator
            if (item.isSymlink) {
                Text(
                    text = "->",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private val dateFormatter = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.SHORT,
    Locale.getDefault(),
)

@Composable
private fun formatDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
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
        diff < 604_800_000 -> pluralStringResource(
            DesignSystemR.plurals.days_ago,
            (diff / 86_400_000).toInt(),
            diff / 86_400_000,
        )
        else -> dateFormatter.format(Date(millis))
    }
}
