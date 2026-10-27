package org.imunav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.core.route.RoadPreference

/** Keep road choice next to the route so drivers can review the actual distance and time. */
@Composable
internal fun RoadPreferencePicker(ui: UiState, enabled: Boolean, offlineAvailable: Boolean, onSelect: (RoadPreference) -> Unit) {
    val resources = LocalResources.current
    Text(stringResource(R.string.road_preference_title), style = MaterialTheme.typography.labelLarge)
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoadPreference.entries.forEach { preference ->
            FilterChip(
                selected = ui.roadPreference == preference,
                onClick = { if (preference != ui.roadPreference) onSelect(preference) },
                enabled = enabled,
                label = { Text(stringResource(roadPreferenceLabel(preference))) },
            )
        }
    }
    Text(
        stringResource(if (offlineAvailable) R.string.road_preference_hint else R.string.road_preference_online_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    if (ui.routeAlternatives.size > 1) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RoadPreference.entries.forEach { preference ->
                ui.routeAlternatives[preference]?.let { route ->
                    OutlinedCard(
                        onClick = { if (preference != ui.roadPreference) onSelect(preference) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().semantics {
                            role = Role.RadioButton
                            selected = ui.roadPreference == preference
                        },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(stringResource(roadPreferenceLabel(preference)), style = MaterialTheme.typography.titleSmall)
                            Text(formatRouteSummary(resources, route), style = MaterialTheme.typography.bodyMedium)
                            val roads = route.steps.asSequence().map { it.name }.filter { it.isNotBlank() }.distinct().take(2).joinToString(" · ")
                            if (roads.isNotEmpty()) Text(roads, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Shared localized labels keep the setting and alternative cards consistent. */
private fun roadPreferenceLabel(preference: RoadPreference): Int = when (preference) {
    RoadPreference.MAIN_ROADS -> R.string.road_preference_main
    RoadPreference.BALANCED -> R.string.road_preference_balanced
}
