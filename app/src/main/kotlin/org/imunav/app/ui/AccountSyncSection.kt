package org.imunav.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.R
import java.text.DateFormat
import java.util.Date

/** Explains location storage before opt-in and keeps account sync separate from tower sharing. */
@Composable
internal fun AccountSyncSection(app: AppGraph) {
    val status by app.accountSync.status.collectAsStateWithLifecycle()
    var confirmDisable by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.account_sync_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.account_sync_summary))
                Text(
                    stringResource(
                        when {
                            !status.signedIn -> R.string.privacy_sign_in_first
                            status.unavailable -> R.string.account_sync_unavailable
                            status.busy -> R.string.account_sync_busy
                            status.failed -> R.string.account_sync_failed
                            status.conflict -> R.string.account_sync_conflict
                            else -> R.string.account_sync_local
                        },
                    ),
                )
                if (status.lastSuccess > 0) Text(stringResource(R.string.sync_last, DateFormat.getDateTimeInstance().format(Date(status.lastSuccess))))
                if (status.enabled) TextButton(onClick = app.accountSync::syncNow, enabled = !status.busy) { Text(stringResource(R.string.action_sync_now)) }
            }
        },
        trailingContent = {
            Switch(checked = status.enabled, enabled = status.signedIn && !status.unavailable, onCheckedChange = {
                if (it) {
                    app.accountSync.requestEnable()
                } else {
                    confirmDisable =
                        true
                }
            })
        },
    )
    if (status.prompt) {
        AlertDialog(
            onDismissRequest = { app.accountSync.decline() },
            title = { Text(stringResource(R.string.account_sync_title)) },
            text = { Text(stringResource(R.string.account_sync_consent) + status.notice.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()) },
            confirmButton = { TextButton(onClick = { app.accountSync.choose(true) }) { Text(stringResource(R.string.account_sync_enable)) } },
            dismissButton = { TextButton(onClick = { app.accountSync.decline() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(R.string.account_sync_disable)) },
            text = { Text(stringResource(R.string.account_sync_delete_notice)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisable = false
                    app.accountSync.choose(false)
                }) { Text(stringResource(R.string.account_sync_disable)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
