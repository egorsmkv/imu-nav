package org.imunav.app.ui

import android.net.TrafficStats
import android.os.Process
import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.core.net.DataUsage

/** Reads this app's system counters only while the expanded settings section is in the foreground. */
@Composable
internal fun DataUsageSettings() {
    SettingsGroup(
        title = stringResource(R.string.data_usage_title),
        summary = stringResource(R.string.data_usage_summary),
        icon = Icons.Filled.SwapVert,
    ) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var usage by remember { mutableStateOf<DataUsage?>(null) }
        var refreshing by remember { mutableStateOf(true) }
        var refreshKey by remember { mutableStateOf(false) }
        LaunchedEffect(lifecycle, refreshKey) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshing = true
                usage = readDataUsage()
                refreshing = false
            }
        }

        SettingsHelp(stringResource(R.string.data_usage_period))
        DataUsageRow(stringResource(R.string.data_usage_received), usage?.receivedBytes, usage == null)
        DataUsageRow(stringResource(R.string.data_usage_sent), usage?.sentBytes, usage == null)
        TextButton(onClick = {
            refreshing = true
            refreshKey = !refreshKey
        }, enabled = !refreshing) {
            Text(stringResource(if (refreshing) R.string.data_usage_loading else R.string.data_usage_refresh))
        }
    }
}

@Composable
private fun DataUsageRow(label: String, bytes: Long?, loading: Boolean) {
    val context = LocalContext.current
    val value = when {
        loading -> stringResource(R.string.data_usage_loading)
        bytes == null -> stringResource(R.string.data_usage_unavailable)
        else -> Formatter.formatFileSize(context, bytes)
    }
    ListItem(headlineContent = { Text(label) }, supportingContent = { Text(value) })
}

/** Binder-backed statistics stay off the main thread; no usage-access permission is needed for our UID. */
private suspend fun readDataUsage(): DataUsage = withContext(Dispatchers.IO) {
    val uid = Process.myUid()
    DataUsage.fromCounters(readCounter { TrafficStats.getUidRxBytes(uid) }, readCounter { TrafficStats.getUidTxBytes(uid) })
}

/** Some devices deny statistics access; preserve an unavailable value instead of reporting zero. */
private inline fun readCounter(read: () -> Long): Long = try {
    read()
} catch (_: SecurityException) {
    TrafficStats.UNSUPPORTED.toLong()
}
