package org.imunav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.power.MapFrameRate

/** A device-local drawing limit, with the effective cap shown when power policy lowers it further. */
@Composable
internal fun MapFrameRateSetting(app: AppGraph) {
    val selected by app.mapFrameRate.collectAsStateWithLifecycle()
    val profile by app.powerProfile.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_fps_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.map_fps_hint))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapFrameRate.entries.forEach { rate ->
                        FilterChip(
                            selected = selected == rate,
                            onClick = { app.setMapFrameRate(rate) },
                            label = { Text(rate.maximumFps?.let { stringResource(R.string.map_fps_value, it) } ?: stringResource(R.string.power_auto)) },
                        )
                    }
                }
                Text(stringResource(R.string.map_fps_effective, profile.mapMaxFps))
            }
        },
    )
}
