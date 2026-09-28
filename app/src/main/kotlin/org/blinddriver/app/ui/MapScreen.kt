package org.blinddriver.app.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.blinddriver.app.AppGraph
import org.blinddriver.app.R
import org.blinddriver.app.UiState
import org.blinddriver.app.cells.TowerLayer
import org.blinddriver.app.service.NavService
import org.blinddriver.core.cells.Radio
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.gnss.GpsState
import org.blinddriver.core.gnss.TrustLevel
import org.blinddriver.core.nav.GuidanceState
import org.blinddriver.core.nav.PositionSource

private val GoodGreen = Color(0xFF1E8E3E)
private val WarnAmber = Color(0xFFE37400)
private val BadRed = Color(0xFFD93025)
private val InfoBlue = Color(0xFF1A73E8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(ui: UiState, g: AppGraph, hasLocation: Boolean, onRequestPermission: () -> Unit, onOpenSettings: () -> Unit, onOpenLog: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val nav = ui.guidance
    val controller = remember { MapController() }
    val towerLayer by g.cells.towerLayer.collectAsStateWithLifecycle()
    val power by g.powerProfile.collectAsStateWithLifecycle()
    val routing by g.offlineRouting.status.collectAsStateWithLifecycle()
    var mapCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var following by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var centeredOnce by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var topInsetPx by remember { mutableStateOf(0) }
    var bottomInsetPx by remember { mutableStateOf(0) }

    // Offer a hand-placed start when there is no trusted position or only a coarse one.
    val pickStart = !nav.active && (!ui.hasTrustedPosition || (ui.trustedAccuracyM ?: 0.0) > 500.0)
    val accuracy = when {
        nav.active -> nav.uncertaintyM
        ui.hasTrustedPosition -> ui.trustedAccuracyM
        else -> null
    }

    LaunchedEffect(nav.active) { if (nav.active) following = true }
    // Jump to the first known position once, so the map opens where the user is.
    LaunchedEffect(ui.currentPosition != null) {
        val p = ui.currentPosition
        if (!centeredOnce && p != null && !nav.active) {
            centeredOnce = true
            controller.moveTo(p, 14.0)
        }
    }
    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            g.clearError()
        }
    }
    LaunchedEffect(ui.cells.message) {
        ui.cells.message?.let { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        val dark = isSystemInDarkTheme()
        key(dark) {
            NavMap(
                controller = controller,
                dark = dark,
                route = nav.route,
                position = ui.currentPosition,
                accuracyM = accuracy,
                bearingDeg = nav.bearingDeg,
                destination = ui.destination ?: nav.destination,
                following = nav.active && following,
                towers = if (ui.cells.showTowers) towerLayer else null,
                onLongPress = { if (!nav.active) g.setDestination(it) },
                onCenterChanged = { mapCenter = it },
                onViewport = { s, w, n, e, z -> g.cells.onViewport(s, w, n, e, z) },
                onUserPan = { if (nav.active) following = false },
                modifier = Modifier.fillMaxSize(),
                insetTopPx = topInsetPx,
                insetBottomPx = bottomInsetPx,
                maxFps = power.mapMaxFps,
                animateCamera = power.animateCamera,
            )
        }

        if (pickStart) Crosshair(Modifier.align(Alignment.Center))

        // ---- Top: maneuver banner, warnings, status pill
        Column(
            Modifier.align(Alignment.TopCenter).onGloballyPositioned { topInsetPx = it.positionInRoot().y.toInt() + it.size.height }
                .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (nav.active && !nav.arrived) ManeuverBanner(nav)
            if (!nav.active) SearchPill(onClick = { showSearch = true })
            when {
                !hasLocation -> WarningBanner(
                    Icons.Filled.LocationOff,
                    stringResource(R.string.permission_title),
                    stringResource(R.string.permission_text),
                    stringResource(R.string.action_allow),
                    onRequestPermission,
                )

                !ui.locationEnabled -> WarningBanner(
                    Icons.Filled.LocationOff,
                    stringResource(R.string.location_off_title),
                    stringResource(R.string.location_off_text),
                    stringResource(R.string.action_turn_on),
                ) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(ui) { showDiagnostics = true }
                Spacer(Modifier.weight(1f))
                if (!nav.active) {
                    FilledTonalIconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.cd_history))
                    }
                    FilledTonalIconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            }
        }

        // ---- Right: map controls
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapButton(Icons.Filled.CellTower, stringResource(R.string.cd_towers), selected = ui.cells.showTowers) {
                g.cells.setShowTowers(!ui.cells.showTowers)
            }
            MapButton(Icons.Filled.Add, stringResource(R.string.cd_zoom_in)) { controller.zoomBy(1.0) }
            MapButton(Icons.Filled.Remove, stringResource(R.string.cd_zoom_out)) { controller.zoomBy(-1.0) }
            val pos = ui.currentPosition
            if (pos != null && !(nav.active && following)) {
                MapButton(Icons.Filled.MyLocation, stringResource(R.string.cd_recenter)) {
                    if (nav.active) following = true else controller.moveTo(pos)
                }
            }
        }

        // ---- Bottom: legend, snackbars, panel
        Column(
            Modifier.align(Alignment.BottomCenter).onGloballyPositioned { bottomInsetPx = it.size.height }
                .navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.cells.showTowers) TowerLegend(towerLayer, ui.cells.radios)
            SnackbarHost(snackbar)
            if (nav.active) {
                NavigationPanel(nav, onStop = {
                    g.stopNavigation()
                    NavService.stop(context)
                }, onReroute = { g.engine.requestManualReroute() })
            } else {
                IdlePanel(
                    ui = ui,
                    routingBusy = routing.busy,
                    pickStart = pickStart,
                    canStart = hasLocation && ui.destination != null && !ui.planning && (ui.hasTrustedPosition || ui.manualStart != null),
                    onSetStart = { mapCenter?.let { g.setManualStart(it) } },
                    onStart = {
                        requestBatteryExemptionOnce(context)
                        g.startNavigation { NavService.start(context) }
                    },
                    onClearDestination = { g.setDestination(null) },
                )
            }
        }
    }

    if (nav.blindDeviation) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.AltRoute, null) },
            title = { Text(stringResource(R.string.deviation_title)) },
            text = { Text(stringResource(R.string.deviation_text, nav.blindDeviationSecLeft)) },
            confirmButton = { TextButton(onClick = { g.engine.confirmDeviation(android.os.SystemClock.elapsedRealtime()) }) { Text(stringResource(R.string.action_reroute_now)) } },
            dismissButton = { TextButton(onClick = { g.engine.dismissDeviation(android.os.SystemClock.elapsedRealtime()) }) { Text(stringResource(R.string.action_on_route)) } },
        )
    }

    if (showSearch) {
        SearchScreen(
            search = g.search,
            near = ui.currentPosition ?: mapCenter,
            onPick = { r ->
                g.search.remember(r)
                g.setDestination(r.point)
                controller.moveTo(r.point, 16.0)
                showSearch = false
            },
            onClose = { showSearch = false },
        )
    }

    if (showDiagnostics) {
        ModalBottomSheet(onDismissRequest = { showDiagnostics = false }) {
            DiagnosticsContent(ui, g, onOpenLog = {
                showDiagnostics = false
                onOpenLog()
            })
        }
    }
}

/**
 * Ask once (at the first trip) to exempt the app from battery optimization, so Android does not
 * stop navigation in the background. Later changes are possible from Settings.
 */
private fun requestBatteryExemptionOnce(context: android.content.Context) {
    val prefs = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("battery_asked", false)) return
    prefs.edit { putBoolean("battery_asked", true) }
    if (isBatteryUnrestricted(context)) return
    runCatching {
        @android.annotation.SuppressLint("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        context.startActivity(intent)
    }
}

fun isBatteryUnrestricted(context: android.content.Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

// ------------------------------------------------------------------ top

@Composable
private fun ManeuverBanner(nav: GuidanceState) {
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
                    Text(stringResource(R.string.then), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Icon(maneuverOf(then).icon, contentDescription = maneuverText(res, then), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun WarningBanner(icon: ImageVector, title: String, text: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Compact "where does my position come from" chip; tap for details. */
@Composable
private fun StatusPill(ui: UiState, onClick: () -> Unit) {
    val res = LocalResources.current
    val nav = ui.guidance
    val (label, dot) = when {
        nav.active -> sourceLabel(res, nav.source) + " · " + formatAccuracy(res, nav.uncertaintyM) to when (nav.source) {
            PositionSource.GPS -> GoodGreen
            PositionSource.GPS_SUSPECT -> WarnAmber
            PositionSource.NONE -> Color.Gray
            else -> InfoBlue
        }

        ui.hasTrustedPosition && ui.trustedFromGps -> stringResource(R.string.src_gps) + " · " + formatAccuracy(res, ui.trustedAccuracyM ?: 0.0) to GoodGreen

        ui.hasTrustedPosition -> stringResource(R.string.src_cells) + " · " + formatAccuracy(res, ui.trustedAccuracyM ?: 0.0) to InfoBlue

        ui.manualStart != null -> stringResource(R.string.idle_position_manual) to InfoBlue

        else -> stringResource(R.string.src_none) to Color.Gray
    }
    val gps = when {
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
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(10.dp))
            Icon(gps.first, contentDescription = gps.second, tint = gps.third, modifier = Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ map controls

@Composable
private fun MapButton(icon: ImageVector, description: String, selected: Boolean = false, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) { Icon(icon, contentDescription = null) }
}

@Composable
private fun Crosshair(modifier: Modifier) {
    Box(modifier.size(44.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TowerLegend(layer: TowerLayer, radios: Set<Radio>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple(Radio.GSM, "2G", Color(0xFF8E24AA)),
                    Triple(Radio.UMTS, "3G", Color(0xFFFB8C00)),
                    Triple(Radio.LTE, "4G", Color(0xFF00897B)),
                    Triple(Radio.NR, "5G", Color(0xFFE53935)),
                ).filter { it.first in radios }.forEach { (_, name, color) ->
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(name, style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.size(12.dp).border(2.dp, BadRed, CircleShape))
                Text(stringResource(R.string.legend_seen_now), style = MaterialTheme.typography.labelMedium)
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

// ------------------------------------------------------------------ bottom panels

@Composable
private fun PanelSurface(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) { content() }
    }
}

@Composable
private fun IdlePanel(ui: UiState, routingBusy: String?, pickStart: Boolean, canStart: Boolean, onSetStart: () -> Unit, onStart: () -> Unit, onClearDestination: () -> Unit) {
    val res = LocalResources.current
    PanelSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (ui.destination == null) R.string.idle_title_choose else R.string.idle_title_ready),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (ui.destination != null) {
                TextButton(onClick = onClearDestination) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
            }
        }
        Spacer(Modifier.size(4.dp))
        val positionLine = when {
            ui.manualStart != null -> stringResource(R.string.idle_position_manual)
            ui.hasTrustedPosition && ui.trustedFromGps -> stringResource(R.string.idle_position_gps, formatAccuracy(res, ui.trustedAccuracyM ?: 0.0))
            ui.hasTrustedPosition -> stringResource(R.string.idle_position_cell, formatAccuracy(res, ui.trustedAccuracyM ?: 0.0))
            ui.gpsRejectReasons.isNotEmpty() -> stringResource(R.string.idle_gps_spoofed)
            else -> stringResource(R.string.idle_waiting_position)
        }
        IconLine(Icons.Filled.TripOrigin, positionLine)
        if (pickStart) IconLine(Icons.Filled.Add, stringResource(R.string.idle_set_start_hint))
        if (ui.destination == null) IconLine(Icons.Filled.Navigation, stringResource(R.string.idle_hint_long_press))
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
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pickStart) {
                OutlinedButton(onClick = onSetStart, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (ui.manualStart == null) R.string.action_set_start else R.string.action_move_start), maxLines = 1)
                }
            }
            Button(onClick = onStart, enabled = canStart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_start), maxLines = 1)
            }
        }
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavigationPanel(nav: GuidanceState, onStop: () -> Unit, onReroute: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    PanelSurface {
        if (nav.arrived) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.arrived), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = onStop) { Text(stringResource(R.string.action_done)) }
            }
            return@PanelSurface
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDuration(res, nav.remainingS),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val arrival = DateFormat.getTimeFormat(context).format(java.util.Date(System.currentTimeMillis() + (nav.remainingS * 1000).toLong()))
                Text(
                    formatDistance(res, nav.remainingM) + " · " + stringResource(R.string.nav_arrival_at, arrival),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nav.rerouting) Text(stringResource(R.string.rerouting), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            }
            SpeedBadge(nav.speedKmh.toInt(), nav.speedLimitKmh)
        }
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onReroute,
                enabled = !nav.rerouting,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_reroute), maxLines = 1, softWrap = false)
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_stop), maxLines = 1)
            }
        }
    }
}

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
                modifier = Modifier.size(46.dp).semantics { contentDescription = res.getString(R.string.speed_limit_cd, limitKmh) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$limitKmh", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ diagnostics sheet

@Composable
private fun DiagnosticsContent(ui: UiState, g: AppGraph, onOpenLog: () -> Unit) {
    val res = LocalResources.current
    val nav = ui.guidance
    val none = stringResource(R.string.none)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleLarge)
        if (nav.active) {
            DiagRow(stringResource(R.string.diag_source), sourceLabel(res, nav.source))
            DiagRow(stringResource(R.string.diag_uncertainty), formatAccuracy(res, nav.uncertaintyM))
            if (!nav.source.isGps) DiagRow(stringResource(R.string.diag_without_gps), stringResource(R.string.diag_seconds, nav.blindS))
        }
        val verdict = ui.lastVerdict
        DiagRow(
            stringResource(R.string.diag_last_fix),
            when (verdict?.level) {
                TrustLevel.GOOD -> stringResource(R.string.verdict_good)
                TrustLevel.SUSPECT -> stringResource(R.string.verdict_suspect)
                TrustLevel.BAD -> stringResource(R.string.verdict_bad)
                null -> none
            },
            valueColor = when (verdict?.level) {
                TrustLevel.GOOD -> GoodGreen
                TrustLevel.SUSPECT -> WarnAmber
                TrustLevel.BAD -> BadRed
                null -> null
            },
        )
        if (!verdict?.reasons.isNullOrEmpty()) {
            DiagRow(stringResource(R.string.diag_reasons), verdict!!.reasons.joinToString(", "), mono = true)
        }
        val gn = ui.gnss
        DiagRow(stringResource(R.string.diag_satellites), "${gn.satellitesUsed} / ${gn.satellitesVisible}")
        DiagRow(stringResource(R.string.diag_signal), gn.meanCn0Used?.let { "%.0f ± %.1f dB-Hz".format(it, gn.cn0SpreadUsed ?: 0f) } ?: none)
        DiagRow(stringResource(R.string.diag_agc), gn.agcDb?.let { "%.1f dB".format(it) } ?: none)
        if (ui.jammed) DiagRow(stringResource(R.string.diag_gps), stringResource(R.string.gps_jammed), valueColor = BadRed)
        DiagRow(
            stringResource(R.string.diag_cells),
            "${ui.cells.located} / ${ui.cells.seen}" + (ui.cells.accuracyM?.let { " · " + formatAccuracy(res, it) } ?: ""),
        )
        DiagRow(stringResource(R.string.diag_sensors), ui.sensorWarning ?: stringResource(R.string.diag_sensors_full))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.simulate_gps_loss), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.simulate_gps_loss_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.simulateGpsLoss, onCheckedChange = { g.setSimulateGpsLoss(it) })
        }
        TextButton(onClick = onOpenLog) { Text(stringResource(R.string.diag_open_log)) }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color? = null, mono: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1.2f),
        )
    }
}
