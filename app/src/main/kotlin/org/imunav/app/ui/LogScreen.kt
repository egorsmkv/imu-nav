package org.imunav.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Categories that reduce a busy diagnostic log to the subsystem the user is investigating. */
private enum class LogFilter(@get:StringRes val label: Int) {
    ALL(R.string.trip_log_filter_all),
    PROBLEMS(R.string.trip_log_filter_problems),
    POSITION(R.string.trip_log_filter_position),
    NAVIGATION(R.string.trip_log_filter_navigation),
}

/** Shows, searches and copies the latest trip-log lines without forcing the user back to the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(app: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lines by remember { mutableStateOf(app.tripLog.recent) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var followNewest by remember { mutableStateOf(true) }
    val list = rememberLazyListState()
    val visibleLines = remember(lines, query, filter) {
        lines.filter { line -> filter.matches(line) && (query.isBlank() || line.contains(query.trim(), ignoreCase = true)) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            lines = app.tripLog.recent
            delay(1000)
        }
    }
    LaunchedEffect(visibleLines.lastOrNull(), followNewest, query, filter) {
        if (followNewest && visibleLines.isNotEmpty()) list.scrollToItem(visibleLines.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trip_log), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } },
                actions = {
                    IconButton(
                        enabled = visibleLines.isNotEmpty(),
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText(resources.getString(R.string.trip_log), visibleLines.joinToString("\n")),
                            )
                            scope.launch {
                                snackbar.showSnackbar(resources.getQuantityString(R.plurals.trip_log_copied, visibleLines.size, visibleLines.size))
                            }
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.trip_log_copy))
                    }
                    IconButton(
                        enabled = visibleLines.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                val file = runCatching { createSharedLogFile(context, visibleLines) }.getOrElse {
                                    snackbar.showSnackbar(resources.getString(R.string.trip_log_share_failed))
                                    return@launch
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(share, resources.getString(R.string.trip_log_share_title)))
                                }.onFailure {
                                    snackbar.showSnackbar(resources.getString(R.string.trip_log_share_failed))
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.trip_log_share))
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
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LogFilter.entries.forEach { choice ->
                            FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(stringResource(choice.label)) })
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            pluralStringResource(R.plurals.trip_log_count, visibleLines.size, visibleLines.size, lines.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = followNewest,
                            onClick = { followNewest = !followNewest },
                            label = { Text(stringResource(R.string.trip_log_follow)) },
                        )
                    }
                }
                when {
                    lines.isEmpty() -> Text(stringResource(R.string.trip_log_empty), modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp))

                    visibleLines.isEmpty() -> Text(stringResource(R.string.trip_log_no_matches), modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp))

                    else -> SelectionContainer {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                            items(visibleLines) { line -> LogLine(line) }
                        }
                    }
                }
            }
        }
    }
}

private const val LOG_CONTROLS_HEIGHT_FRACTION = 0.5f

/** A compact timestamp and message row that highlights failures without changing the log text. */
@Composable
private fun LogLine(line: String) {
    val hasPrefix = "] " in line
    val message = if (hasPrefix) line.substringAfter("] ") else line
    val prefix = if (hasPrefix) line.substringBefore("] ") + "]" else ""
    Surface(color = if (isProblemLog(message)) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f) else Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (prefix.isNotEmpty()) {
                Text(prefix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Text(message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    HorizontalDivider()
}

/** True when [line] belongs to this high-level diagnostic category. */
private fun LogFilter.matches(line: String): Boolean {
    val message = line.substringAfter("] ").lowercase(Locale.US)
    return when (this) {
        LogFilter.ALL -> true
        LogFilter.PROBLEMS -> isProblemLog(message)
        LogFilter.POSITION -> listOf("gps", "cell", "signal", "compass", "position", "agps", "net_").any(message::contains)
        LogFilter.NAVIGATION -> listOf("nav", "route", "turn", "trip", "blind", "reroute", "manual_start", "start_accuracy").any(message::contains)
    }
}

/** Detect log keys that normally require attention, for filtering and a subtle warning background. */
private fun isProblemLog(line: String): Boolean = listOf("fail", "error", "reject", "lost", "off_route", "discard", "spoof").any(line.lowercase(Locale.US)::contains)

/** Writes the filtered log snapshot off the main thread so Android can share it as a text attachment. */
private suspend fun createSharedLogFile(context: Context, lines: List<String>): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "log-shares").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    File(directory, "imu-nav-log-$stamp.txt").apply {
        bufferedWriter().use { writer ->
            lines.forEach { line ->
                writer.appendLine(line)
            }
        }
    }
}
