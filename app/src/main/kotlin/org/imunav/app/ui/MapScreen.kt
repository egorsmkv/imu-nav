package org.imunav.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.MapStartMode
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.bookmarks.Bookmarks
import org.imunav.app.cells.TowerLayer
import org.imunav.core.cells.Radio
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.TravelMode
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import java.util.Date

private val GoodGreen = Color(0xFF1E8E3E)
private val WarnAmber = Color(0xFFE37400)
private val BadRed = Color(0xFFD93025)
private val InfoBlue = Color(0xFF1A73E8)
private const val ROUTE_FIELDS_MIN_WIDTH_DP = 480
private const val MAP_HEADER_HEIGHT_FRACTION = 0.4f
private const val MAP_CONTROLS_HEIGHT_FRACTION = 0.45f
private const val SPEED_SIGN_TEXT_HEIGHTS = 3
private val MapButtonSize = 48.dp
private val MapButtonSpacing = 10.dp
private val MapOverlayPadding = 12.dp

/**
 * The main screen: full-screen map with status on top, map buttons on the right, and either the
 * route editor or the navigation panel at the bottom.
 *
 * @param ui everything to show (re-drawn whenever it changes)
 * @param app for actions (start navigation, search, settings…)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    ui: UiState,
    app: AppGraph,
    hasLocation: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    mapActive: Boolean = true,
    controlsVisible: Boolean = true,
    overlayStartPx: Int = 0,
) {
    val context = LocalContext.current
    // Short taps of the vibrator confirm the actions that matter while driving (follows the phone's touch-feedback setting).
    val haptic = LocalHapticFeedback.current
    val res = LocalResources.current
    val nav = ui.guidance
    val controller = remember { MapController() }
    val towerLayer by app.cells.towerLayer.collectAsStateWithLifecycle()
    val power by app.powerProfile.collectAsStateWithLifecycle()
    val travelMode by app.travelMode.collectAsStateWithLifecycle()
    val offlineMapStatus by app.offlineMap.status.collectAsStateWithLifecycle()
    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.tripLog.write("activity_permission granted=$granted")
    }
    val routing by app.offlineRouting.status.collectAsStateWithLifecycle()
    var mapCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var following by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var searchTarget by remember { mutableStateOf<RoutePoint?>(null) }
    var searchWidth by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    // Opening view: the last known GPS / trusted position, or the fixed place from Settings.
    val startView = remember { app.mapStart.initialView() }
    val startMode by app.mapStartMode.collectAsStateWithLifecycle()
    var centeredOnce by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val panelScroll = rememberScrollState()
    val headerScroll = rememberScrollState()
    var topInsetPx by remember { mutableIntStateOf(0) }
    var bottomInsetPx by remember { mutableIntStateOf(0) }
    var bottomInsetWidthPx by remember { mutableIntStateOf(0) }

    // Offer a hand-placed start when there is no trusted position or only a coarse one.
    val pickStart = !nav.active && (!ui.hasTrustedPosition || (ui.trustedAccuracyM ?: 0.0) > 500.0)
    val accuracy = when {
        nav.active -> nav.uncertaintyM
        ui.hasTrustedPosition -> ui.trustedAccuracyM
        else -> null
    }

    LaunchedEffect(nav.active) { if (nav.active) following = true }
    // A newly opened navigation panel or warning must start with its summary visible.
    LaunchedEffect(nav.active, nav.arrived) { panelScroll.scrollTo(0) }
    LaunchedEffect(nav.active, hasLocation, ui.locationEnabled) { headerScroll.scrollTo(0) }
    // GPS mode: jump to the first live trusted position once, so the map shows where the user is.
    // Fixed mode keeps the chosen place; the re-centre button still goes to the position.
    LaunchedEffect(ui.currentPosition != null) {
        val p = ui.currentPosition
        if (!centeredOnce && p != null && !nav.active && startMode == MapStartMode.GPS) {
            centeredOnce = true
            controller.moveTo(p, 14.0)
        }
    }
    LaunchedEffect(ui.mapFocusRequest, mapActive) {
        if (mapActive) {
            ui.mapFocusRequest?.let { point ->
                if (!nav.active) {
                    controller.moveTo(point, 16.0)
                    // Saved walking routes need the same step-counter permission as the mode selector.
                    if (travelMode == TravelMode.FOOT && Build.VERSION.SDK_INT >= 29 && !hasActivityPermission(context)) {
                        activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
                app.clearMapFocusRequest()
            }
        }
    }
    LaunchedEffect(ui.error, mapActive) {
        ui.error?.takeIf { mapActive }?.let {
            snackbar.showSnackbar(it)
            app.clearError()
        }
    }
    LaunchedEffect(ui.cells.message) {
        ui.cells.message?.let { snackbar.showSnackbar(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = drivingPaneWidth(maxWidth, maxHeight)
        val landscape = paneWidth != null
        val shortWindow = maxWidth > maxHeight
        val showControls = controlsVisible && searchTarget == null
        val overlayWidth = if (searchTarget != null) searchWidth else overlayStartPx
        val cameraTop = if (showControls && !landscape) topInsetPx else 0
        val cameraBottom = if (showControls && !landscape) bottomInsetPx else 0
        val cameraStart = if (showControls && landscape) bottomInsetWidthPx else overlayWidth
        val showRecenter = ui.currentPosition != null && !(nav.active && following)
        val mapButtonCount = if (showRecenter) 5 else 4
        val controlsHeight = MapButtonSize * mapButtonCount + MapButtonSpacing * (mapButtonCount - 1)
        val topInset = with(LocalDensity.current) { cameraTop.toDp() }
        val bottomInset = with(LocalDensity.current) { bottomInsetPx.toDp() }
        val availableHeight = (maxHeight - topInset).coerceAtLeast(0.dp)
        // In short windows both the control rail and the panel can scroll; neither may consume the other.
        val reservedControlsHeight = minOf(controlsHeight + MapOverlayPadding * 2, availableHeight * MAP_CONTROLS_HEIGHT_FRACTION)
        val panelMaxHeight = if (landscape) availableHeight else availableHeight - reservedControlsHeight
        val controlsMaxHeight = if (landscape) availableHeight else (availableHeight - bottomInset).coerceAtLeast(0.dp)
        val panelMaxWidth = paneWidth ?: maxWidth
        val dark = isSystemInDarkTheme()
        NavMap(
            controller = controller,
            dark = dark,
            route = nav.route ?: ui.previewRoute,
            position = ui.currentPosition,
            accuracyM = accuracy,
            bearingDeg = nav.bearingDeg,
            destination = ui.destination ?: nav.destination,
            following = nav.active && following,
            towers = if (ui.cells.showTowers) towerLayer else null,
            onLongPress = {
                if (!nav.active) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    app.setDestination(it)
                }
            },
            onCenterChanged = {
                mapCenter = it
                app.lastMapCenter = it
            },
            onViewport = { s, w, n, e, z -> app.cells.onViewport(s, w, n, e, z) },
            onUserPan = { if (nav.active) following = false },
            modifier = Modifier.fillMaxSize(),
            insetTopPx = cameraTop,
            insetBottomPx = cameraBottom,
            cameraInsetStartPx = cameraStart,
            cameraInsetBottomPx = cameraBottom,
            maxFps = power.mapMaxFps,
            animateCamera = power.animateCamera,
            initialCenter = startView.point,
            initialZoom = startView.zoom,
            followZoomDefault = if (nav.travelMode == TravelMode.FOOT) 17.5 else 16.0,
            offlineStyleJson = if (offlineMapStatus.offlineInUse) app.offlineMap.styleJson(dark) else null,
            active = mapActive && (searchTarget == null || landscape),
        )

        if (showControls) {
            if (pickStart) {
                val crosshairModifier = if (landscape) {
                    Modifier.align(Alignment.Center).offset { IntOffset(cameraStart / 2, cameraTop / 2) }
                } else {
                    Modifier.align(Alignment.Center).offset { IntOffset(0, (topInsetPx - bottomInsetPx) / 2) }
                }
                Crosshair(crosshairModifier)
            }

            // ---- Top: maneuver banner, warnings, status pill
            if (!landscape) {
                Column(
                    Modifier.align(Alignment.TopCenter)
                        .onGloballyPositioned { topInsetPx = it.positionInRoot().y.toInt() + it.size.height }
                        .heightIn(max = maxHeight * MAP_HEADER_HEIGHT_FRACTION)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            StatusPill(ui) { showDiagnostics = true }
                        }
                        if (!nav.active) {
                            FilledTonalIconButton(onClick = onOpenHistory) {
                                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.cd_history))
                            }
                            FilledTonalIconButton(onClick = onOpenSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                            }
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(headerScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (nav.active && !nav.arrived) {
                            if (shortWindow) {
                                nav.nextStep?.let { step ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text(
                                            formatDistance(res, nav.distToNextM) + " · " + instructionLine(res, step),
                                            Modifier.padding(8.dp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            } else {
                                ManeuverBanner(nav)
                            }
                        }
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
                    }
                }
            }

            // ---- Right: map controls
            val mapControlsModifier = if (landscape) {
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 12.dp)
            } else {
                Modifier.align(Alignment.CenterEnd).offset { IntOffset(0, (topInsetPx - bottomInsetPx) / 2) }.padding(end = 12.dp)
            }
            Column(
                Modifier.heightIn(
                    max = controlsMaxHeight,
                ).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).then(mapControlsModifier).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MapButtonSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MapButton(Icons.Filled.Bookmark, stringResource(R.string.bookmarks), onClick = onOpenBookmarks)
                MapButton(Icons.Filled.CellTower, stringResource(R.string.cd_towers), selected = ui.cells.showTowers) {
                    app.cells.setShowTowers(!ui.cells.showTowers)
                }
                MapButton(Icons.Filled.Add, stringResource(R.string.cd_zoom_in)) { controller.zoomBy(1.0) }
                MapButton(Icons.Filled.Remove, stringResource(R.string.cd_zoom_out)) { controller.zoomBy(-1.0) }
                val pos = ui.currentPosition
                if (pos != null && showRecenter) {
                    MapButton(Icons.Filled.MyLocation, stringResource(R.string.cd_recenter)) {
                        if (nav.active) following = true else controller.moveTo(pos)
                    }
                }
            }

            // ---- Bottom: legend, snackbars, panel
            Column(
                Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter).onGloballyPositioned {
                    bottomInsetPx = it.size.height
                    bottomInsetWidthPx = it.size.width
                }
                    .heightIn(max = panelMaxHeight)
                    .then(if (landscape) Modifier.widthIn(max = panelMaxWidth) else Modifier)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ).navigationBarsPadding().then(if (landscape) Modifier.statusBarsPadding() else Modifier).padding(MapOverlayPadding).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shortWindow) {
                    if (nav.active) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                app.stopNavigation()
                            }) { Text(stringResource(R.string.action_stop)) }
                            OutlinedButton(onClick = { app.engine.requestManualReroute() }, enabled = !nav.rerouting) { Text(stringResource(R.string.action_reroute)) }
                        }
                    } else if (landscape) {
                        Button(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            requestBatteryExemptionOnce(context)
                            app.startNavigation()
                        }, enabled = hasLocation && ui.destination != null && !ui.planning && (ui.hasTrustedPosition || ui.manualStart != null)) {
                            Text(stringResource(R.string.action_start))
                        }
                    }
                    if (!landscape) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.driving_collapse else R.string.driving_expand))
                        }
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(panelScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (landscape) {
                        StatusPill(ui) { showDiagnostics = true }
                        if (nav.active && !nav.arrived) ManeuverBanner(nav)
                        if (!nav.active) {
                            Row {
                                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.cd_settings)) }
                                IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, stringResource(R.string.cd_history)) }
                                if (!hasLocation) {
                                    TextButton(onClick = onRequestPermission) { Text(stringResource(R.string.action_allow)) }
                                } else if (!ui.locationEnabled) {
                                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                                        Text(stringResource(R.string.action_turn_on))
                                    }
                                }
                            }
                        }
                    }
                    if (ui.cells.showTowers) TowerLegend(towerLayer, ui.cells.radios)
                    SnackbarHost(snackbar)
                    if (!shortWindow || landscape || expanded) {
                        if (nav.active) {
                            NavigationPanel(nav, showActions = !shortWindow, onStop = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                app.stopNavigation()
                            }, onReroute = { app.engine.requestManualReroute() })
                        } else {
                            IdlePanel(
                                ui = ui,
                                bookmarks = app.bookmarks,
                                mode = travelMode,
                                walkingAvailable = routing.walking,
                                onModeChange = { mode ->
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    app.setTravelMode(mode)
                                    // The step counter needs the "physical activity" permission; without it walking still works at a fixed pace.
                                    if (mode == TravelMode.FOOT && Build.VERSION.SDK_INT >= 29 && !hasActivityPermission(context)) {
                                        activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                    }
                                },
                                routingBusy = routing.busy,
                                compact = landscape,
                                showStart = !landscape,
                                pickStart = pickStart,
                                canStart = hasLocation && ui.destination != null && !ui.planning && (ui.hasTrustedPosition || ui.manualStart != null),
                                onSetStart = {
                                    mapCenter?.let {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        app.setManualStart(it)
                                    }
                                },
                                onSearchStart = { searchTarget = RoutePoint.START },
                                onSearchDestination = { searchTarget = RoutePoint.DESTINATION },
                                onClearStart = { app.setManualStart(null) },
                                onStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    requestBatteryExemptionOnce(context)
                                    app.startNavigation()
                                },
                                onClearDestination = { app.setDestination(null) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (nav.blindDeviation && mapActive) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.AutoMirrored.Filled.AltRoute, null) },
            title = { Text(stringResource(R.string.deviation_title)) },
            text = { Text(stringResource(R.string.deviation_text, nav.blindDeviationSecLeft), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { app.engine.confirmDeviation(SystemClock.elapsedRealtime()) }) { Text(stringResource(R.string.action_reroute_now)) } },
            dismissButton = { TextButton(onClick = { app.engine.dismissDeviation(SystemClock.elapsedRealtime()) }) { Text(stringResource(R.string.action_on_route)) } },
        )
    }

    searchTarget?.let { target ->
        DrivingOverlay(onWidth = { searchWidth = it }) {
            SearchScreen(
                search = app.search,
                bookmarks = app.bookmarks,
                onBookmarkPick = { place ->
                    if (app.useBookmark(place, target == RoutePoint.START)) searchTarget = null
                },
                near = if (target == RoutePoint.DESTINATION) ui.manualStart ?: ui.currentPosition ?: mapCenter else ui.currentPosition ?: mapCenter,
                hint = stringResource(if (target == RoutePoint.START) R.string.search_from_hint else R.string.search_to_hint),
                onPick = { r ->
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    app.search.remember(r)
                    when (target) {
                        RoutePoint.START -> app.setManualStart(r.point, r.routePointLabel())
                        RoutePoint.DESTINATION -> app.setDestination(r.point, r.routePointLabel())
                    }
                    controller.moveTo(r.point, 16.0)
                    searchTarget = null
                },
                onClose = { searchTarget = null },
            )
        }
    }

    if (showDiagnostics) {
        ModalBottomSheet(onDismissRequest = { showDiagnostics = false }) {
            DiagnosticsContent(ui, app, onOpenLog = {
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
private fun requestBatteryExemptionOnce(context: Context) {
    val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    if (prefs.getBoolean("battery_asked", false)) return
    prefs.edit { putBoolean("battery_asked", true) }
    if (isBatteryUnrestricted(context)) return
    runCatching {
        @android.annotation.SuppressLint("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        context.startActivity(intent)
    }
}

/** Is the app exempt from battery optimisation? */
fun isBatteryUnrestricted(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

// ------------------------------------------------------------------ top

/** Big banner at the top during navigation: maneuver icon, distance and street. */
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
private fun WarningBanner(icon: ImageVector, title: String, text: String, action: String, onAction: () -> Unit) {
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
private fun StatusPill(ui: UiState, onClick: () -> Unit) {
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

        ui.hasTrustedPosition && ui.trustedFromGps -> stringResource(R.string.src_gps) + " · " + formatAccuracy(res, ui.trustedAccuracyM ?: 0.0) to GoodGreen

        ui.hasTrustedPosition -> stringResource(R.string.src_cells) + " · " + formatAccuracy(res, ui.trustedAccuracyM ?: 0.0) to InfoBlue

        ui.manualStart != null -> stringResource(R.string.idle_position_manual) to InfoBlue

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

/** Round button on the map's right edge (zoom, re-centre, towers). */
@Composable
private fun MapButton(icon: ImageVector, description: String, selected: Boolean = false, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(MapButtonSize).semantics { contentDescription = description },
    ) { Icon(icon, contentDescription = null) }
}

/** The "+" in the middle of the map, used to place the start by hand. */
@Composable
private fun Crosshair(modifier: Modifier) {
    Box(modifier.size(44.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Legend for the cell-tower layer (colours per radio type and how many are drawn). */
@Composable
private fun TowerLegend(layer: TowerLayer, radios: Set<Radio>) {
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
private fun IdlePanel(
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
private enum class RoutePoint { START, DESTINATION }

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
private fun hasActivityPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
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
private fun NavigationPanel(nav: GuidanceState, showActions: Boolean, onStop: () -> Unit, onReroute: () -> Unit) {
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

// ------------------------------------------------------------------ diagnostics sheet

/** Contents of the diagnostics sheet: GPS verdict, satellites, jamming, cells, debug switches. */
@Composable
private fun DiagnosticsContent(ui: UiState, app: AppGraph, onOpenLog: () -> Unit) {
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
        val reasons = verdict?.reasons.orEmpty()
        if (reasons.isNotEmpty()) DiagRow(stringResource(R.string.diag_reasons), reasons.joinToString(", "), mono = true)
        val gn = ui.gnss
        DiagRow(stringResource(R.string.diag_satellites), "${gn.satellitesUsed} / ${gn.satellitesVisible}")
        DiagRow(stringResource(R.string.diag_signal), gn.meanCn0Used?.let { "%.0f ± %.1f dB-Hz".format(it, gn.cn0SpreadUsed ?: 0f) } ?: none)
        DiagRow(stringResource(R.string.diag_agc), gn.agcDb?.let { "%.1f dB".format(it) } ?: none)
        when {
            ui.simulateGpsLoss -> DiagRow(stringResource(R.string.diag_gps), stringResource(R.string.gps_loss_test_active), valueColor = InfoBlue)
            ui.jammed -> DiagRow(stringResource(R.string.diag_gps), stringResource(R.string.gps_jammed), valueColor = BadRed)
        }
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
            Switch(checked = ui.simulateGpsLoss, onCheckedChange = { app.setSimulateGpsLoss(it) })
        }
        TextButton(onClick = onOpenLog) { Text(stringResource(R.string.diag_open_log)) }
    }
}

/** One "label: value" row in the diagnostics sheet. */
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
