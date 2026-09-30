package org.imunav.app.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.imunav.app.R
import org.imunav.app.cells.CellUsageHistory
import java.text.DateFormat
import java.util.Date

/** Recent positioning contributions with explicit, user-initiated sharing of the full persisted CSV. */
@Composable
internal fun CellHistorySection(history: CellUsageHistory) {
    val status by history.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.cell_history_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cell_history_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.cell_history_count, status.total))
        if (status.total == 0L) Text(stringResource(R.string.cell_history_empty))
        if (status.failed || failed) Text(stringResource(R.string.cell_history_failed), color = MaterialTheme.colorScheme.error)
        if (sharing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            enabled = status.total > 0 && !sharing,
            onClick = {
                sharing = true
                failed = false
                scope.launch {
                    try {
                        runCatching {
                            val file = history.export()
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, resources.getString(R.string.cell_history_share)))
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            failed = true
                        }
                    } finally {
                        sharing = false
                    }
                }
            },
        ) { Text(stringResource(R.string.cell_history_share)) }
        if (status.recent.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.cell_history_recent)) }
        }
        if (expanded) {
            val dateFormat = remember(resources.configuration) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
            status.recent.forEach { record ->
                val observation = record.contribution.observation
                val key = observation.key
                Text(
                    stringResource(
                        R.string.cell_history_entry,
                        dateFormat.format(Date(record.timeMs)),
                        key.radio.name,
                        key.mcc,
                        key.mnc,
                        key.area,
                        key.cid,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
