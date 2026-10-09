package org.imunav.app.ui

import android.net.TrafficStats
import android.os.Process
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.core.net.DataUsage
import java.text.NumberFormat

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

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DataUsageOverview(usage)
            OutlinedButton(onClick = {
                refreshing = true
                refreshKey = !refreshKey
            }, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (refreshing) R.string.data_usage_loading else R.string.data_usage_refresh))
            }
            Text(
                stringResource(R.string.data_usage_period),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A prominent total and wrapping direction cards keep the figures readable on small screens and large fonts. */
@Composable
private fun DataUsageOverview(usage: DataUsage?) {
    val loading = usage == null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.data_usage_total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formattedDataUsage(usage?.totalBytes, loading),
            style = if (usage?.totalBytes != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.data_usage_since_restart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val fraction = usage?.receivedFraction
    if (fraction != null) {
        TrafficSplit(fraction)
    } else if (usage?.totalBytes == 0L) {
        Text(stringResource(R.string.data_usage_empty), style = MaterialTheme.typography.bodyMedium)
    }
    val minimumCardWidth = 144.dp * LocalDensity.current.fontScale
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
    ) {
        DataUsageCard(
            stringResource(R.string.data_usage_received),
            formattedDataUsage(usage?.receivedBytes, loading),
            Icons.Filled.ArrowDownward,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Modifier.weight(1f).widthIn(min = minimumCardWidth),
        )
        DataUsageCard(
            stringResource(R.string.data_usage_sent),
            formattedDataUsage(usage?.sentBytes, loading),
            Icons.Filled.ArrowUpward,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Modifier.weight(1f).widthIn(min = minimumCardWidth),
        )
    }
}

@Composable
private fun DataUsageCard(label: String, value: String, icon: ImageVector, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The bar is a traffic ratio, not a download-progress or data-plan-limit indicator. */
@Composable
private fun TrafficSplit(receivedFraction: Float) {
    val locale = LocalResources.current.configuration.locales[0]
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.tertiary)) {
            Box(Modifier.fillMaxWidth(receivedFraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        // Text carries the same information for screen readers without misleading progress semantics.
        Text(
            stringResource(R.string.data_usage_split, percent.format(receivedFraction), percent.format(1f - receivedFraction)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Android chooses short decimal units and locale-aware separators using the selected in-app language. */
@Composable
private fun formattedDataUsage(bytes: Long?, loading: Boolean): String {
    val context = LocalContext.current
    val configuration = LocalResources.current.configuration
    val localizedContext = remember(context, configuration) { context.createConfigurationContext(configuration) }
    return when {
        loading -> stringResource(R.string.data_usage_loading)
        bytes == null -> stringResource(R.string.data_usage_unavailable)
        else -> Formatter.formatShortFileSize(localizedContext, bytes)
    }
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
