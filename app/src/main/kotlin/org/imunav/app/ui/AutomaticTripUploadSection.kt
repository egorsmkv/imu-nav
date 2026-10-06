package org.imunav.app.ui

import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.R

/** The server's current archive consent is required before enabling automatic location uploads. */
@Composable
internal fun AutomaticTripUploadSection(app: AppGraph) {
    val status by app.automaticTripUpload.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.automaticTripUpload.refresh() }
    ListItem(
        headlineContent = { Text(stringResource(R.string.trip_archive_auto)) },
        supportingContent = {
            Text(
                stringResource(
                    when {
                        status.failed -> R.string.trip_archive_auto_failed
                        status.busy && status.enabled -> R.string.trip_archive_progress
                        !status.available -> R.string.trip_archive_auto_unavailable
                        else -> R.string.trip_archive_auto_description
                    },
                ),
            )
        },
        trailingContent = {
            Checkbox(
                checked = status.enabled,
                enabled = status.enabled || (status.available && !status.busy),
                onCheckedChange = app.automaticTripUpload::setEnabled,
            )
        },
    )
}
