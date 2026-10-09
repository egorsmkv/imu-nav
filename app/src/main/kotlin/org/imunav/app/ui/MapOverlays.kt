package org.imunav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.cells.TowerLayer
import org.imunav.core.cells.Radio
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource

// ------------------------------------------------------------------ top

/** Big banner at the top during navigation: maneuver icon, distance and street. */
@Composable
internal fun ManeuverBanner(nav: GuidanceState) {
    val res = LocalResources.current
    val step = nav.nextStep ?: return
    val m = maneuverOf(step)
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(m.icon, contentDescription = null, modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatDistance(res, nav.distToNextM), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(maneuverText(res, step), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (step.name.isNotBlank()) {
                        Text(step.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            nav.thenStep?.let { then ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.then), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Icon(maneuverOf(then).icon, contentDescription = maneuverText(res, then), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A card explaining a problem (e.g. Location is off) with one action button. */
@Composable
internal fun WarningBanner(icon: ImageVector, title: String, text: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Compact "where does my position come from" chip; tap for details. */
@Composable
internal fun StatusPill(ui: UiState, onClick: () -> Unit) {
    val res = LocalResources.current
    val nav = ui.guidance
    val (label, dot) = when {
        ui.simulateGpsLoss -> stringResource(R.string.gps_loss_test_active) to InfoBlue

        nav.active -> sourceLabel(res, nav.source) + " · " + formatAccuracy(res, nav.uncertaintyM) to when (nav.source) {
            PositionSource.GPS -> GoodGreen
            PositionSource.GPS_SUSPECT -> WarnAmber
            PositionSource.NONE -> Color.Gray
            else -> InfoBlue
        }

        ui.manualStart != null -> stringResource(R.string.idle_position_manual) to InfoBlue

        ui.hasTrustedPosition && ui.trustedFromGps -> stringResource(R.string.src_gps) + " · " + formatAccuracy(res, ui.trustedAccuracyM ?: 0.0) to GoodGreen

        ui.hasTrustedPosition -> stringResource(R.string.src_cells) + " · " + formatAccuracy(res, ui.trustedAccuracyM ?: 0.0) to InfoBlue

        else -> stringResource(R.string.src_none) to Color.Gray
    }
    val gps = when {
        ui.simulateGpsLoss -> Triple(Icons.Filled.GpsOff, stringResource(R.string.gps_loss_test_active), InfoBlue)
        ui.jammed -> Triple(Icons.Filled.GpsOff, stringResource(R.string.gps_jammed), BadRed)
        ui.lastVerdict?.level == TrustLevel.BAD -> Triple(Icons.Filled.GpsOff, stringResource(R.string.gps_rejected), BadRed)
        ui.gpsState == GpsState.OK -> Triple(Icons.Filled.GpsFixed, stringResource(R.string.gps_ok), GoodGreen)
        ui.gpsState == GpsState.DEGRADED -> Triple(Icons.Filled.GpsNotFixed, stringResource(R.string.gps_weak), WarnAmber)
        else -> Triple(Icons.Filled.GpsOff, stringResource(R.string.gps_lost), Color.Gray)
    }
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Icon(gps.first, contentDescription = gps.second, tint = gps.third, modifier = Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ map controls

/** Round button on the map's right edge (zoom, re-center, towers). */
@Composable
internal fun MapButton(icon: ImageVector, description: String, selected: Boolean = false, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(MapButtonSize).semantics { contentDescription = description },
    ) { Icon(icon, contentDescription = null) }
}

/** The "+" in the middle of the map, used to place the start by hand. */
@Composable
internal fun Crosshair(modifier: Modifier) {
    Box(modifier.size(44.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Legend for the cell-tower layer (colors per radio type and how many are drawn). */
@Composable
internal fun TowerLegend(layer: TowerLayer, radios: Set<Radio>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple(Radio.GSM, "2G", Color(0xFF8E24AA)),
                    Triple(Radio.UMTS, "3G", Color(0xFFFB8C00)),
                    Triple(Radio.LTE, "4G", Color(0xFF00897B)),
                    Triple(Radio.NR, "5G", Color(0xFFE53935)),
                ).filter { it.first in radios }.forEach { (_, name, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).border(2.dp, BadRed, CircleShape))
                    Text(stringResource(R.string.legend_seen_now), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                when {
                    layer.zoomTooLow -> stringResource(R.string.legend_zoom_in)
                    layer.truncated -> stringResource(R.string.legend_sample, layer.towers.size)
                    else -> pluralStringResource(R.plurals.legend_count, layer.towers.size, layer.towers.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
