package org.imunav.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.imunav.app.R
import org.imunav.core.util.LogEntry

/** Categories remain independent: a failed GPS event belongs to both Position and Problems. */
private enum class LogFilter(@get:StringRes val label: Int) {
    ALL(R.string.trip_log_filter_all),
    PROBLEMS(R.string.trip_log_filter_problems),
    POSITION(R.string.trip_log_filter_position),
    NAVIGATION(R.string.trip_log_filter_navigation),
    ;

    fun matches(entry: LogEntry): Boolean = when (this) {
        ALL -> true
        PROBLEMS -> entry.isProblem
        POSITION -> entry.isPosition
        NAVIGATION -> entry.isNavigation
    }
}

/** Stateless log source allows the viewer to be exercised without starting sensors or navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogViewer(
    lines: List<String>,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onCopy: (List<String>) -> Unit,
    onShare: (List<String>) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var followNewest by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val entries = remember(lines) { lines.map(LogEntry::parse) }
    val visible = remember(entries, query, filter) {
        entries.filter { filter.matches(it) && (query.isBlank() || it.raw.contains(query.trim(), ignoreCase = true)) }
    }
    val rowKeys = remember(visible) {
        val occurrences = mutableMapOf<String, Int>()
        visible.map { entry ->
            val occurrence = occurrences.getOrDefault(entry.raw, 0)
            occurrences[entry.raw] = occurrence + 1
            "$occurrence:${entry.raw}"
        }
    }
    LaunchedEffect(dragged) {
        if (dragged) followNewest = false
    }
    LaunchedEffect(visible, followNewest, dragged) {
        if (followNewest && !dragged && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trip_log), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                actions = {
                    IconButton(enabled = visible.isNotEmpty(), onClick = { onCopy(visible.map { it.raw }) }) {
                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.trip_log_copy))
                    }
                    IconButton(enabled = visible.isNotEmpty(), onClick = { onShare(visible.map { it.raw }) }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.trip_log_share))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val controlsMaxHeight = maxHeight * LOG_CONTROLS_HEIGHT_FRACTION
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.trip_log_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, stringResource(R.string.cd_clear)) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LogFilter.entries.forEach { choice ->
                            FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(stringResource(choice.label)) })
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !paused,
                            onClick = { onPausedChange(!paused) },
                            label = { Text(stringResource(if (paused) R.string.trip_log_paused else R.string.trip_log_live)) },
                            leadingIcon = { Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null) },
                        )
                        FilterChip(selected = followNewest, onClick = { followNewest = !followNewest }, label = { Text(stringResource(R.string.trip_log_follow)) })
                    }
                    Text(
                        pluralStringResource(R.plurals.trip_log_count, visible.size, visible.size, lines.size),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (visible.isEmpty()) {
                        LogEmptyState(lines.isEmpty(), onReset = {
                            query = ""
                            filter = LogFilter.ALL
                        })
                    } else {
                        LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxSize().testTag("log_events"),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Keep the reading anchor when the bounded tail rolls over; duplicates need distinct keys.
                            itemsIndexed(visible, key = { index, _ -> rowKeys[index] }) { _, entry ->
                                LogEntryCard(entry, onCopy = { onCopy(listOf(entry.raw)) })
                            }
                        }
                        if (!followNewest) {
                            FilledTonalButton(onClick = { followNewest = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                                Text(stringResource(R.string.trip_log_latest), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val LOG_CONTROLS_HEIGHT_FRACTION = 0.5f
private const val LOG_PREVIEW_LINES = 3

/** Empty and filtered states explain how to recover, including when the keyboard leaves little room. */
@Composable
private fun LogEmptyState(empty: Boolean, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (empty) R.string.trip_log_empty else R.string.trip_log_no_matches), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (!empty) TextButton(onClick = onReset) { Text(stringResource(R.string.trip_log_reset_filters)) }
    }
}

/** Separate readable metadata from exact diagnostic text, with expansion and per-event copying. */
@Composable
private fun LogEntryCard(entry: LogEntry, onCopy: () -> Unit) {
    var expanded by remember(entry.raw) { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = if (entry.isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.timestamp.isNotEmpty()) Text(entry.timestamp, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                if (entry.isProblem) LogBadge(R.string.trip_log_attention)
                if (entry.isPosition) LogBadge(R.string.trip_log_filter_position)
                if (entry.isNavigation) LogBadge(R.string.trip_log_filter_navigation)
            }
            if (expanded) {
                SelectionContainer { LogMessage(entry.message, Int.MAX_VALUE) }
                entry.elapsedMs?.let { Text(stringResource(R.string.trip_log_elapsed, it), style = MaterialTheme.typography.labelSmall) }
            } else {
                LogMessage(entry.message, LOG_PREVIEW_LINES)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.trip_log_collapse else R.string.trip_log_details))
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.trip_log_copy_event)) }
            }
        }
    }
}

/** Category text supplements color so the hints remain useful with color vision deficiencies. */
@Composable
private fun LogBadge(@StringRes label: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        Text(stringResource(label), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** Wrap long diagnostic values while keeping the original spelling and punctuation. */
@Composable
private fun LogMessage(message: String, maxLines: Int) {
    Text(message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
