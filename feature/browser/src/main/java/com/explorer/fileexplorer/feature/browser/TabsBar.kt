package com.explorer.fileexplorer.feature.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowserTabsBar(
    tabs: List<BrowserTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit,
    onReorder: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabBounds = remember { mutableStateMapOf<Long, Rect>() }
    var reorderingId by remember { mutableStateOf<Long?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val tabId = tab.id
                    val latestIndex = index
                    TabChip(
                        tab = tab,
                        selected = index == selectedIndex,
                        isReordering = reorderingId == tabId,
                        swipeThreshold = swipeThreshold,
                        startPosition = tabBounds[tabId]?.center?.x ?: 0f,
                        modifier = Modifier.onGloballyPositioned {
                            tabBounds[tabId] = it.boundsInRoot()
                        },
                        onSelect = { currentOnSelect(latestIndex) },
                        onClose = { currentOnClose(latestIndex) },
                        onReorderStart = { reorderingId = tabId },
                        onReorderDragEnd = { position ->
                            if (reorderingId == tabId) {
                                val target = tabs.indices.firstOrNull { targetIndex ->
                                    tabBounds[tabs[targetIndex].id]?.let { position < it.center.x } == true
                                } ?: latestIndex
                                currentOnReorder(latestIndex, target)
                                reorderingId = null
                            }
                        },
                    )
                }
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(DesignSystemR.string.new_tab))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    tab: BrowserTab,
    selected: Boolean,
    isReordering: Boolean,
    swipeThreshold: Float,
    startPosition: Float,
    modifier: Modifier,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onReorderStart: () -> Unit,
    onReorderDragEnd: (Float) -> Unit,
) {
    val label = tab.path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .widthIn(min = 104.dp, max = 190.dp)
            .alpha(if (isReordering) 0.6f else 1f)
            .pointerInput(tab.id, swipeThreshold) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        distance += amount
                    },
                    onDragEnd = {
                        if (abs(distance) >= swipeThreshold) onClose()
                    },
                    onDragCancel = {},
                )
            }
            .pointerInput(tab.id) {
                var position = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        onReorderStart()
                        position = startPosition
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        position += amount.x
                    },
                    onDragEnd = { onReorderDragEnd(position) },
                    onDragCancel = {},
                )
            }
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(DesignSystemR.string.close_tab, label), modifier = Modifier.size(16.dp))
            }
        }
    }
}
