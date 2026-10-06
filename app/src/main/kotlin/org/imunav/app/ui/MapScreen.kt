package org.imunav.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.MapStartMode
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode

internal val GoodGreen = Color(0xFF1E8E3E)
internal val WarnAmber = Color(0xFFE37400)
internal val BadRed = Color(0xFFD93025)
internal val InfoBlue = Color(0xFF1A73E8)
private const val MAP_HEADER_HEIGHT_FRACTION = 0.4f
private const val MAP_CONTROLS_HEIGHT_FRACTION = 0.45f
internal val MapButtonSize = 48.dp
internal val MapButtonSpacing = 10.dp
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
        ui.manualStart != null -> null
        ui.hasTrustedPosition -> ui.trustedAccuracyM
        else -> null
    }

    LaunchedEffect(nav.active) { if (nav.active) following = true }
    // A newly opened navigation panel or warning must start with its summary visible.
    LaunchedEffect(nav.active, nav.arrived) { panelScroll.scrollTo(0) }
    LaunchedEffect(nav.active, hasLocation, ui.locationEnabled) { headerScroll.scrollTo(0) }
    // GPS mode: jump to the first live trusted position once, so the map shows where the user is.
    // Fixed mode keeps the chosen place; the re-centre button still goes to the position.
    LaunchedEffect(ui.currentPosition != null, controller.map) {
        val p = ui.currentPosition
        if (!centeredOnce && controller.map != null && p != null && !nav.active && startMode == MapStartMode.GPS) {
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
            prefetchZoomDelta = power.mapPrefetchZoomDelta,
            animateCamera = power.animateCamera,
            initialCenter = startView.point,
            initialZoom = startView.zoom,
            followZoomDefault = if (nav.travelMode == TravelMode.FOOT) 17.5 else 16.0,
            offlineStyleJson = if (offlineMapStatus.offlineInUse) app.offlineMap.styleJson(dark, mapCenter ?: startView.point) else null,
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
