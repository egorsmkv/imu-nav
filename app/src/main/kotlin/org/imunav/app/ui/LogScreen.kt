package org.imunav.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import kotlin.time.Duration.Companion.milliseconds

/** Shows, searches and copies the latest trip-log lines without forcing the user back to the bottom. */
@Composable
fun LogScreen(app: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lines by remember { mutableStateOf(app.tripLog.recent) }
    var paused by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingShareLines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(app.tripLog, lifecycleOwner, paused) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!paused) {
                while (true) {
                    lines = app.tripLog.recent
                    delay(1000.milliseconds)
                }
            }
        }
    }
    LogViewer(
        lines = lines,
        paused = paused,
        onPausedChange = { paused = it },
        onBack = onBack,
        snackbar = snackbar,
        onCopy = { visibleLines ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText(resources.getString(R.string.trip_log), visibleLines.joinToString("\n")),
            )
            scope.launch {
                snackbar.showSnackbar(resources.getQuantityString(R.plurals.trip_log_copied, visibleLines.size, visibleLines.size))
            }
        },
        onShare = { pendingShareLines = it.toList() },
    )
    pendingShareLines?.let { shareLines ->
        AlertDialog(
            onDismissRequest = { pendingShareLines = null },
            title = { Text(stringResource(R.string.trip_log_share_title)) },
            text = { Text(stringResource(R.string.trip_log_share_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingShareLines = null
                    scope.launch {
                        val file = runCatching { createSharedLogFile(context, shareLines) }.getOrElse {
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
                }) { Text(stringResource(R.string.trip_log_share_title)) }
            },
            dismissButton = { TextButton(onClick = { pendingShareLines = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

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
