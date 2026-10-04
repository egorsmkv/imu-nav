package org.imunav.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.bookmarks.Bookmarks
import org.imunav.core.nav.GuidanceState
import org.imunav.core.route.TravelMode
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import java.util.Date

private const val ROUTE_FIELDS_MIN_WIDTH_DP = 480
private const val SPEED_SIGN_TEXT_HEIGHTS = 3

// ------------------------------------------------------------------ bottom panels

/** Rounded card inside the bounded, scrollable map panel, shared by route planning and navigation. */
@Composable
private fun PanelSurface(compact: Boolean = false, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 12.dp else 20.dp)) { content() }
    }
}

/** Bottom panel before navigation: position status, hints and the Start / Start here buttons. */
@Composable
internal fun IdlePanel(
    ui: UiState,
    bookmarks: Bookmarks,
    mode: TravelMode,
    walkingAvailable: Boolean,
    onModeChange: (TravelMode) -> Unit,
    routingBusy: String?,
    compact: Boolean,
    showStart: Boolean,
    pickStart: Boolean,
    canStart: Boolean,
    onSetStart: () -> Unit,
    onSearchStart: () -> Unit,
    onSearchDestination: () -> Unit,
    onClearStart: () -> Unit,
    onStart: () -> Unit,
    onClearDestination: () -> Unit,
) {
    val res = LocalResources.current
    val startValue = when {
        ui.manualStartLabel != null -> ui.manualStartLabel
        ui.manualStart != null -> stringResource(R.string.route_point_on_map)
        ui.hasTrustedPosition -> stringResource(R.string.route_current_position)
        else -> stringResource(R.string.search_from_hint)
    }
    val destinationValue = when {
        ui.destinationLabel != null -> ui.destinationLabel
        ui.destination != null -> stringResource(R.string.route_point_on_map)
        else -> stringResource(R.string.search_to_hint)
    }
    val startHint = stringResource(if (ui.manualStart == null) R.string.idle_set_start_hint else R.string.idle_move_start_hint)
    PanelSurface(compact) {
        Text(
            stringResource(if (ui.destination == null) R.string.idle_title_choose else R.string.idle_title_ready),
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
        RoutePointEditor(
            startValue = startValue,
            destinationValue = destinationValue,
            compact = compact,
            onSearchStart = onSearchStart,
            onSearchDestination = onSearchDestination,
            onClearStart = onClearStart.takeIf { ui.manualStart != null },
            onClearDestination = onClearDestination.takeIf { ui.destination != null },
        )
        var more by remember { mutableStateOf(false) }
        if (!compact || more) BookmarkSaveActions(ui, mode, bookmarks)
        if (compact) TextButton(onClick = { more = !more }) { Text(stringResource(R.string.driving_more)) }
        val positionLine = when {
            ui.manualStart != null -> stringResource(R.string.idle_position_manual)
            ui.hasTrustedPosition && ui.trustedFromGps -> stringResource(R.string.idle_position_gps, formatAccuracy(res, ui.trustedAccuracyM ?: 0.0))
            ui.hasTrustedPosition -> stringResource(R.string.idle_position_cell, formatAccuracy(res, ui.trustedAccuracyM ?: 0.0))
            ui.gpsRejectReasons.isNotEmpty() -> stringResource(R.string.idle_gps_spoofed)
            else -> stringResource(R.string.idle_waiting_position)
        }
        if (!compact) IconLine(Icons.Filled.TripOrigin, positionLine)
        if (pickStart) IconLine(Icons.Filled.Add, startHint)
        if (!compact && ui.destination == null) IconLine(Icons.Filled.Navigation, stringResource(R.string.idle_hint_long_press))
        Spacer(Modifier.size(if (compact) 8.dp else 12.dp))
        TravelModeSelector(mode, walkingAvailable, onModeChange)
        if (!walkingAvailable) {
            Text(
                stringResource(R.string.mode_walk_needs_pack),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (routingBusy != null) {
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(routingBusy, style = MaterialTheme.typography.bodySmall)
        }
        if (ui.planning) {
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.planning), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(if (compact) 8.dp else 12.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pickStart) {
                OutlinedButton(onClick = onSetStart) {
                    Text(stringResource(if (ui.manualStart == null) R.string.action_set_start else R.string.action_move_start))
                }
            }
            if (showStart) {
                Button(onClick = onStart, enabled = canStart) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_start))
                }
            }
        }
    }
}

/** Start and destination fields are side by side when vertical space is scarce. */
@Composable
private fun RoutePointEditor(
    startValue: String,
    destinationValue: String,
    compact: Boolean,
    onSearchStart: () -> Unit,
    onSearchDestination: () -> Unit,
    onClearStart: (() -> Unit)?,
    onClearDestination: (() -> Unit)?,
) {
    BoxWithConstraints {
        val sideBySide = compact && maxWidth / LocalDensity.current.fontScale >= ROUTE_FIELDS_MIN_WIDTH_DP.dp
        Column {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutePointField(
                        icon = Icons.Filled.TripOrigin,
                        label = stringResource(R.string.route_from),
                        value = startValue,
                        onClick = onSearchStart,
                        onClear = onClearStart,
                        modifier = Modifier.weight(1f),
                    )
                    RoutePointField(
                        icon = Icons.Filled.Navigation,
                        label = stringResource(R.string.route_to),
                        value = destinationValue,
                        onClick = onSearchDestination,
                        onClear = onClearDestination,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                RoutePointField(
                    icon = Icons.Filled.TripOrigin,
                    label = stringResource(R.string.route_from),
                    value = startValue,
                    onClick = onSearchStart,
                    onClear = onClearStart,
                )
                Spacer(Modifier.size(8.dp))
                RoutePointField(
                    icon = Icons.Filled.Navigation,
                    label = stringResource(R.string.route_to),
                    value = destinationValue,
                    onClick = onSearchDestination,
                    onClear = onClearDestination,
                )
            }
        }
    }
    Spacer(Modifier.size(8.dp))
}

/** One searchable route endpoint. Clearing the origin returns it to automatic positioning. */
@Composable
private fun RoutePointField(icon: ImageVector, label: String, value: String, onClick: () -> Unit, onClear: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onClear != null) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear)) }
            }
        }
    }
}

/** Which endpoint a search result should replace. */
internal enum class RoutePoint { START, DESTINATION }

/** Compact address shown in the route editor after a search result is selected. */
fun SearchResult.routePointLabel(): String = if (kind == ResultKind.PLACE || subtitle.isBlank()) title else "$title, $subtitle"

/** Car / Walk choice before starting. Walking is disabled when the map pack has no walking data. */
@Composable
private fun TravelModeSelector(mode: TravelMode, walkingAvailable: Boolean, onModeChange: (TravelMode) -> Unit) {
    val options = listOf(
        Triple(TravelMode.CAR, Icons.Filled.DirectionsCar, R.string.mode_car),
        Triple(TravelMode.FOOT, Icons.AutoMirrored.Filled.DirectionsWalk, R.string.mode_walk),
    )
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, icon, label) ->
            FilterChip(
                modifier = Modifier.semantics { role = Role.RadioButton },
                selected = mode == option,
                onClick = { onModeChange(option) },
                enabled = option == TravelMode.CAR || walkingAvailable,
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/** Has the user allowed step counting ("physical activity")? */
internal fun hasActivityPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

/** One line of text with a small leading icon. */
@Composable
private fun IconLine(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bottom panel during navigation: time and distance left, speed, Reroute and Stop. */
@Composable
internal fun NavigationPanel(nav: GuidanceState, showActions: Boolean, onStop: () -> Unit, onReroute: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    PanelSurface {
        if (nav.arrived) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.arrived), style = MaterialTheme.typography.titleLarge)
                Button(onClick = onStop) { Text(stringResource(R.string.action_done)) }
            }
            return@PanelSurface
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(
                    formatDuration(res, nav.remainingS),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val arrival = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (nav.remainingS * 1000).toLong()))
                Text(
                    formatDistance(res, nav.remainingM) + " · " + stringResource(R.string.nav_arrival_at, arrival),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nav.rerouting) Text(stringResource(R.string.rerouting), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            }
            SpeedBadge(nav.speedKmh.toInt(), nav.speedLimitKmh)
        }
        if (showActions) {
            Spacer(Modifier.size(14.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReroute,
                    enabled = !nav.rerouting,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(stringResource(R.string.action_reroute))
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_stop))
                }
            }
        }
    }
}

/** Current speed, plus a round speed-limit sign (red ring, like the road sign) when the limit is known. */
@Composable
private fun SpeedBadge(speedKmh: Int, limitKmh: Int?) {
    val res = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$speedKmh", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.unit_kmh, speedKmh).substringAfter(' '), style = MaterialTheme.typography.labelSmall)
        }
        if (limitKmh != null) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                contentColor = Color.Black,
                border = BorderStroke(4.dp, BadRed),
                modifier = Modifier.size(maxOf(46.dp, with(LocalDensity.current) { 16.sp.toDp() } * SPEED_SIGN_TEXT_HEIGHTS)).semantics {
                    contentDescription =
                        res.getString(R.string.speed_limit_cd, limitKmh)
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$limitKmh", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
