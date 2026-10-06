package org.imunav.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.LogManagement
import org.imunav.app.R

/** Explains automatic cleanup before users choose limits; immediate deletion needs confirmation. */
@Composable
internal fun LogManagementSection(app: AppGraph) {
    val manager = app.tripLog.management
    val status by manager.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { manager.refresh() }
    SectionHeader(stringResource(R.string.logs_management))
    SettingsHelp(stringResource(R.string.logs_management_summary))
    ListItem(
        headlineContent = { Text(stringResource(R.string.logs_storage)) },
        supportingContent = {
            Column {
                status.storage?.let { Text(stringResource(R.string.logs_usage, it.fileCount, Formatter.formatFileSize(context, it.bytes))) }
                if (status.busy) Text(stringResource(R.string.logs_working))
                if (status.failed) Text(stringResource(R.string.logs_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = { TextButton(onClick = manager::refresh, enabled = !status.busy) { Text(stringResource(R.string.logs_refresh)) } },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.logs_size_limit)) },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogManagement.STORAGE_LIMITS_MB.forEach { limit ->
                    FilterChip(
                        selected = status.limitMb == limit,
                        enabled = !status.busy,
                        onClick = { manager.setStorageLimit(limit) },
                        label = { Text(if (limit == 0) stringResource(R.string.logs_unlimited) else stringResource(R.string.logs_limit_mb, limit)) },
                    )
                }
            }
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.logs_retention)) },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogManagement.RETENTION_DAYS.forEach { days ->
                    FilterChip(
                        selected = status.retentionDays == days,
                        enabled = !status.busy,
                        onClick = { manager.setRetentionDays(days) },
                        label = { Text(if (days == 0) stringResource(R.string.logs_forever) else pluralStringResource(R.plurals.logs_days, days, days)) },
                    )
                }
            }
        },
    )
    TextButton(onClick = { confirmClear = true }, enabled = !status.busy) { Text(stringResource(R.string.logs_clear)) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.logs_clear)) },
            text = { Text(stringResource(R.string.logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    manager.clear()
                    app.refresh()
                }) { Text(stringResource(R.string.logs_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
