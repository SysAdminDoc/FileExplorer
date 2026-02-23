package com.explorer.fileexplorer.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val segments = parsePath(currentPath)

    // Auto-scroll to end when path changes
    LaunchedEffect(currentPath) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Root
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = "Root",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onNavigate("/") },
            )

            for ((index, segment) in segments.withIndex()) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )

                val isLast = index == segments.lastIndex
                Text(
                    text = segment.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isLast) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = !isLast) { onNavigate(segment.path) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private data class PathSegment(val name: String, val path: String)

private fun parsePath(path: String): List<PathSegment> {
    val cleaned = path.trimEnd('/')
    if (cleaned.isEmpty() || cleaned == "/") return emptyList()

    val parts = cleaned.split("/").filter { it.isNotEmpty() }
    val segments = mutableListOf<PathSegment>()
    var accumulated = ""
    for (part in parts) {
        accumulated = "$accumulated/$part"
        segments.add(PathSegment(part, accumulated))
    }
    return segments
}
