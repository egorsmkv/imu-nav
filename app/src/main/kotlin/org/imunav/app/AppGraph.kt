package org.imunav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.imunav.app.alerts.AirAlerts
import org.imunav.app.bookmarks.BookmarkDatabase
import org.imunav.app.bookmarks.Bookmarks
import org.imunav.app.cells.CellManager
import org.imunav.app.diagnostics.DevDiagnostics
import org.imunav.app.diagnostics.ProfileCapture
import org.imunav.app.haptics.Haptics
import org.imunav.app.maps.OfflineMap
import org.imunav.app.nativecore.NativeEstimatorBridge
import org.imunav.app.nativecore.NativeNetworkTracker
import org.imunav.app.nativecore.NativeRouteGeometry
import org.imunav.app.nativecore.NativeRouteProjector
import org.imunav.app.nativecore.NativeSpeedFusion
import org.imunav.app.nativecore.NativeTrustEvaluator
import org.imunav.app.navigation.NavigationSessionLifecycle
import org.imunav.app.navigation.NavigationWork
import org.imunav.app.navigation.PreviewResult
import org.imunav.app.navigation.RoutePreviewCoordinator
import org.imunav.app.navigation.RoutePreviewRequest
import org.imunav.app.navigation.withPreparedResource
import org.imunav.app.net.ProxySettings
import org.imunav.app.obd.ObdLink
import org.imunav.app.power.PowerMode
import org.imunav.app.power.PowerPolicy
import org.imunav.app.power.PowerProfile
import org.imunav.app.routing.OfflineRouting
import org.imunav.app.routing.OsrmRouter
import org.imunav.app.routing.Router
import org.imunav.app.routing.SmartRouter
import org.imunav.app.search.PlaceSearch
import org.imunav.app.sensors.SensorHub
import org.imunav.app.service.NavService
import org.imunav.app.trips.TripManager
import org.imunav.app.voice.Voice
import org.imunav.core.Tuning
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.car.DisplayOwnership
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.PlanningPosition
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.eskf.InertialShadow
import org.imunav.core.nav.EnglishPhrases
import org.imunav.core.nav.NavAlert
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.nav.RussianPhrases
import org.imunav.core.nav.UkrainianPhrases
import org.imunav.core.route.TravelMode
import org.imunav.core.speed.SpeedProfile
import org.json.JSONObject
import java.util.Locale

/**
 * The app's "object graph": creates every long-lived component once and connects them.
 *
 * There is one instance per process, created in [BlindDriverApp.onCreate] and reachable from any
 * `Context` as `context.graph`. This is plain manual dependency injection — no framework —
 * so you can read top to bottom how the pieces fit:
 *
 * ```
 * SensorHub (Android GPS/sensors) ─┐
 * CellScanner (cell towers) ───────┼─► PositioningHub ─► NavigationEngine ─► UiState ─► Compose UI
 *                                  │   (trust checks)    (dead reckoning)
 * OfflineRouting / OSRM ───────────┘ (routes)                 └─► Voice, TripManager (recording)
 * ```
 *
 * Threading rule: everything here, and all engine access, happens on the **main thread**
 * (background work is done in coroutines and its results are posted back).
 */
class AppGraph(private val context: Context) {
    /** Initialize networking preferences before any manager constructs an HTTP request. */
    val proxySettings = ProxySettings(context)

    /** Coroutine scope for the app's lifetime; runs on the main thread unless told otherwise. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tripLog = TripLog(context)

    /** Local saved places/routes load on IO and outlive activity recreation. */
    val bookmarks = Bookmarks(BookmarkDatabase(context), scope)

    /** UI and voice language: the in-app choice, or the phone's language by default. */
    private val voicePrefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    val voiceEnabled = MutableStateFlow(voicePrefs.getBoolean("enabled", true))
    private val voice = Voice(context, AppLanguage.locale(AppLanguage.get(context)), voiceEnabled.value)

    /** Vibration patterns for turns and alerts during navigation. */
    val haptics = Haptics(context)
    val language = MutableStateFlow(AppLanguage.get(context))

    /** Switch UI + voice language; the activity recreates itself to pick up new resources. */
    fun setLanguage(choice: String) {
        AppLanguage.set(context, choice)
        language.value = choice
        voice.setLocale(AppLanguage.locale(choice))
        engine.phrases = phrasesFor()
        tripLog.write("language $choice")
    }

    /** Enable or silence spoken navigation while keeping visual guidance unchanged. */
    fun setVoiceEnabled(enabled: Boolean) {
        voicePrefs.edit { putBoolean("enabled", enabled) }
        voiceEnabled.value = enabled
        voice.setEnabled(enabled)
        tripLog.write("voice enabled=$enabled")
    }

    /** Voice phrases in the current language. */
    private fun phrasesFor() = when (AppLanguage.locale(AppLanguage.get(context)).language) {
        "uk" -> UkrainianPhrases
        "ru" -> RussianPhrases
        else -> EnglishPhrases
    }

    /** Offline GraphHopper pack first; OSRM online only as an allowed fallback. */
    val offlineRouting = OfflineRouting(context, scope, tripLog::write)

    /** Offline map display: a downloaded map pack, and maps saved along planned routes. */
    val offlineMap = OfflineMap(context, scope, tripLog::write)

    /** Is the phone in dark mode? (Decides which map style a route download uses.) */
    private fun darkTheme(): Boolean = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Address search: the pack's offline index, Photon online when allowed. */
    val search = PlaceSearch(context, offlineRouting::search, { offlineRouting.status.value.searchAvailable }, { offlineRouting.status.value.allowOnline })
    private val router: Router = SmartRouter(
        offlineRouting,
        OsrmRouter(),
        tripLog::write,
        noOfflineMessage = { context.getString(R.string.routing_no_coverage) },
        noWalkingMessage = { context.getString(R.string.routing_no_walking) },
    )

    // ---------------------------------------------------------------- travel mode

    private val navigationPreferences = NavigationPreferences(context)

    /** Car or on foot, chosen before starting (remembered between app starts). */
    val travelMode = navigationPreferences.travelMode

    fun setTravelMode(mode: TravelMode) {
        if (engine.state.active || _ui.value.startingNavigation) return
        navigationPreferences.setTravelMode(mode)
        tripLog.write("travel_mode $mode")
        planRoutePreview()
    }

    /** Fallback used when GPS is unavailable; hybrid preserves the original app behaviour. */
    val navigationMethod = navigationPreferences.navigationMethod

    fun setNavigationMethod(method: NavigationMethod) {
        navigationPreferences.setNavigationMethod(method)
        tripLog.write("navigation_method $method")
    }

    val navigationEstimator: StateFlow<NavigationEstimator> = navigationPreferences.navigationEstimator
    val inertialExperiment: StateFlow<Boolean> = navigationPreferences.inertialExperiment
    private var inertialShadow: InertialShadow? = null

    /** Raw inertial shadow is opt-in, car-only, and cannot take ownership of live guidance. */
    fun setInertialExperiment(enabled: Boolean) {
        if (engine.state.active || _ui.value.planning) return
        navigationPreferences.setInertialExperiment(enabled)
    }

    /** Change only between trips so state and covariance cannot jump mid-navigation. */
    fun setNavigationEstimator(estimator: NavigationEstimator) {
        if (engine.state.active || _ui.value.planning) return
        navigationPreferences.setNavigationEstimator(estimator)
        tripLog.write("navigation_estimator $estimator")
    }

    /** Engine thresholds (factory defaults; see [Tuning]), plus the user's terrain-matching choice. */
    val tuning = navigationPreferences.tuning

    /** Settings: compare the barometer with the route's hills (routing packs with elevation). */
    fun setTerrainMatch(on: Boolean) {
        navigationPreferences.setTerrainMatch(on)
        tripLog.write("terrain_match $on")
    }

    /** Geography does not determine GPS trust; receiver and motion checks still reject spoofed fixes. */
    val serviceArea: ServiceArea = ServiceArea.EVERYWHERE
    private val nativeTrustEvaluator = NativeTrustEvaluator(serviceArea)
    val hub = PositioningHub(area = serviceArea, trustEvaluator = nativeTrustEvaluator, jammingDetector = nativeTrustEvaluator).also { it.log = tripLog::write }
    private val nativeEstimator = NativeEstimatorBridge(tripLog::write)
    private val nativeNetworkTracker = NativeNetworkTracker.create()
    private val nativeRouteProjector = NativeRouteProjector()

    /** Where the map opens (Settings → Map start). */
    val mapStart = MapStartPrefs(context, serviceArea)
    val mapStartMode = MutableStateFlow(mapStart.mode)
    val mapStartFixed = MutableStateFlow(mapStart.fixed)

    /** Centre of the map as last seen by the map screen, for "use map centre" in Settings. */
    @Volatile var lastMapCenter: GeoPoint? = null

    /** Save the map-start setting (Settings → Map start). */
    fun setMapStart(mode: MapStartMode, fixed: GeoPoint?) {
        mapStart.mode = mode
        mapStart.fixed = fixed
        mapStartMode.value = mode
        mapStartFixed.value = fixed
        tripLog.write("map_start $mode ${fixed?.let { "%.5f %.5f".format(Locale.US, it.lat, it.lon) }.orEmpty()}")
    }

    /** How the engine reaches the rest of the app: voice, log, and routing requests. */
    private val listener = object : NavListener {
        override fun onSay(text: String, urgent: Boolean) = voice.speak(text, urgent)
        override fun onAlert(alert: NavAlert) = haptics.play(alert)
        override fun onLog(message: String) = tripLog.write(message)
        override fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {
            var committing = false
            navigationWork.launch(onFailure = {
                tripLog.write("reroute_failed ${it.message}")
                if (committing) stopNavigation() else engine.rerouteFailed()
                _ui.value = _ui.value.copy(error = it.message)
            }, onFinished = {
                // Includes a router that cancels itself: permit a later reroute attempt.
                engine.rerouteFailed()
            }) {
                val route = router.route(from, destination, via, engine.mode)
                ensureCurrent()
                withPreparedResource(create = { NativeRouteGeometry.create(route) }) { prepared ->
                    ensureCurrent()
                    if (engine.state.active) {
                        committing = true
                        val uncertainty = engine.state.uncertaintyM
                        nativeRouteProjector.install(route, prepared.value)
                        prepared.transfer()
                        engine.setRoute(route, SystemClock.elapsedRealtime())
                        nativeEstimator.replaceRoute(prepared.value, engine.progressS, uncertainty)
                        trips.onRoute(route)
                        runCatching { offlineMap.saveCorridor(route, darkTheme()) }.onFailure { tripLog.write("corridor_save_failed ${it.message}") }
                    }
                }
            }
        }
    }

    /** The dead-reckoning navigator (pure Kotlin, in the `core` module). */
    val engine: NavigationEngine = NavigationEngine(
        tuning = { tuning.value },
        navigationMethod = { navigationMethod.value },
        speedProfile = SpeedProfile(PrefsSpeedProfileStore(context)),
        phrases = phrasesFor(),
        listener = listener,
        networkTracker = nativeNetworkTracker,
        routeProjector = nativeRouteProjector,
        speedFusion = NativeSpeedFusion,
        nativeEstimator = nativeEstimator,
    )

    /** Trip recording (for replay), history, and restoring a trip after the app was killed. */
    val trips = TripManager(context, hub, engine, tripLog::write)

    /** Android GPS / network location / motion sensors → [hub] and [engine]. */
    val sensors = SensorHub(
        context,
        hub,
        onImu = {
            engine.onImu(it, hub.gyroBias.biasDegS)
            trips.onImu(it)
        },
        log = tripLog::write,
        onStep = { elapsedMs ->
            engine.onStep(elapsedMs)
            trips.onStep(elapsedMs)
        },
        onPressure = { hPa, elapsedMs ->
            engine.onPressure(hPa.toDouble(), elapsedMs)
            trips.onPressure(hPa, elapsedMs)
        },
    )

    /** The car's own speed from a Bluetooth OBD-II adapter (Settings → Car speed), used while driving. */
    val obd = ObdLink(context, tripLog::write) { kmh, elapsedMs ->
        engine.onVehicleSpeed(kmh.toDouble(), elapsedMs)
        nativeEstimator.onVehicleSpeed(kmh.toDouble(), elapsedMs)
        trips.onVehicleSpeed(kmh, elapsedMs)
    }

    // Kotlin convention: a private mutable flow (_ui) and a public read-only view (ui) of it.
    private val _ui = MutableStateFlow(UiState())

    /** The latest preview calculation; replacing it prevents an old result winning a race. */
    private val routePreview = RoutePreviewCoordinator(scope, router)
    private val navigationWork = NavigationWork(scope)

    /** What the UI shows; collect it with `collectAsStateWithLifecycle()`. */
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---------------------------------------------------------------- offline cell positioning

    /** Offline cell-tower positioning: tower database, scanning, downloads, sharing. */
    val cells = CellManager(context, scope, hub, tripLog::write)

    /** Opt-in air-raid notifications are streamed only while the phone app is visible. */
    val airAlerts = AirAlerts(context, scope, cells)

    /** Opt-in trip diagnostics use the same signed-in server but have separate local and server storage. */
    val diagnostics = DevDiagnostics(context, cells)

    /** Local performance capture shared only through Android's chooser. */
    val profileCapture = ProfileCapture(context)

    /** Owns the foreground service, trip recorder, native resources, engine, and OBD session as one transaction. */
    private val navigationSession =
        NavigationSessionLifecycle(context, tripLog, nativeRouteProjector, nativeEstimator, engine, trips, obd, voice, ::applyPower, cells::maybeAutoSync)

    // ---------------------------------------------------------------- power

    /** Battery trade-offs (Settings → Battery). */
    val power = PowerPolicy(context)
    private val _powerProfile = MutableStateFlow(power.resolve())

    /** The profile in effect (AUTO already resolved). */
    val powerProfile: StateFlow<PowerProfile> = _powerProfile.asStateFlow()
    val powerMode = MutableStateFlow(power.mode)
    val keepScreenOn = MutableStateFlow(power.keepScreenOn)

    /** Connected car hosts still need guidance updates while their map surface is hidden. */
    private val displays = DisplayOwnership()
    val uiVisible: Boolean get() = displays.hasConsumer
    private var idleRefresh: Job? = null

    /** Update display ownership without giving either display a second engine loop. */
    fun setPhoneVisible(visible: Boolean) {
        displays.phone(visible)
        airAlerts.setVisible(visible)
        updateDisplays()
    }

    fun setCarVisible(id: String, visible: Boolean) {
        displays.car(id, visible)
        updateDisplays()
    }

    fun disconnectCar(id: String) {
        displays.disconnect(id)
        updateDisplays()
    }

    private fun updateDisplays() {
        if (displays.needsSensing(engine.state.active)) startSensing() else stopSensing()
        if (displays.visible && idleRefresh == null) {
            idleRefresh = scope.launch {
                while (isActive) {
                    if (!engine.state.active) refresh()
                    delay(NavigationEngine.TICK_MS * powerProfile.value.uiEveryTicks)
                }
            }
        } else if (!displays.visible) {
            idleRefresh?.cancel()
            idleRefresh = null
        }
    }
    private var tickCount = 0L

    init {
        hub.inertialObserver = { inertialShadow?.onSensor(it) }
        hub.judgedFixObserver = { if (!engine.simulateGpsLoss) inertialShadow?.onGps(it) }
        cells.scanner.intervalMs = {
            val p = _powerProfile.value
            when {
                !engine.state.active && PlanningPosition.select(SystemClock.elapsedRealtime(), hub.lastGood, hub.lastNet)?.source == FixSource.GPS -> p.cellScanIdleMs
                !engine.state.active -> p.cellScanNoGpsMs
                hub.gpsState == GpsState.OK -> p.cellScanGoodGpsMs
                else -> p.cellScanNoGpsMs
            }
        }
    }

    /** Save the power mode and apply it immediately. */
    fun setPowerMode(mode: PowerMode) {
        power.mode = mode
        powerMode.value = mode
        tripLog.write("power_mode $mode")
        applyPower()
    }

    /** Keep the display on while navigating, or not. */
    fun setKeepScreenOn(on: Boolean) {
        power.keepScreenOn = on
        keepScreenOn.value = on
    }

    /** Re-evaluate the profile (battery level / charger / battery saver for AUTO) and apply it. */
    fun applyPower() {
        val p = power.resolve()
        _powerProfile.value = p
        val inertial = engine.state.active && engine.mode == TravelMode.CAR && inertialExperiment.value
        if (inertial && inertialShadow == null) {
            inertialShadow = InertialShadow()
            tripLog.write("eskf_shadow status=waiting_for_inputs")
        } else if (!inertial) {
            inertialShadow = null
        }
        sensors.configure(p, navigating = engine.state.active, walking = engine.state.active && engine.mode == TravelMode.FOOT, inertial = inertial)
    }

    /** Start GPS, sensors and cell scans (when the app is visible or navigating). */
    fun startSensing() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        applyPower()
        sensors.start()
        cells.scanner.start()
    }

    /** Stop them again to save battery. */
    fun stopSensing() {
        if (displays.needsSensing(engine.state.active) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        sensors.stop()
        cells.scanner.stop()
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Best trusted position for planning: a GOOD GPS fix, else a network/cell fix. The fused provider is
     * deliberately ignored — Android builds it largely from GPS, so it inherits spoofed positions.
     */
    fun currentPosition(): GeoPoint? = PlanningPosition.select(SystemClock.elapsedRealtime(), hub.lastGood, hub.lastNet)?.point

    /** Use [p] as the route origin instead of the trusted position; [label] is from address search. */
    fun setManualStart(p: GeoPoint?, label: String? = null) {
        if (engine.state.active || _ui.value.startingNavigation) return
        tripLog.write("manual_start ${p?.let { "%.5f %.5f".format(Locale.US, it.lat, it.lon) }}")
        _ui.value = _ui.value.copy(manualStart = p, manualStartLabel = label.takeIf { p != null }, error = null)
        planRoutePreview()
    }

    /** The error message was shown; forget it. */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** Destination picked on the map or in search; [label] is shown for a search result. */
    fun setDestination(p: GeoPoint?, label: String? = null) {
        if (engine.state.active || _ui.value.startingNavigation) return
        _ui.value = _ui.value.copy(destination = p, destinationLabel = label.takeIf { p != null }, error = null)
        planRoutePreview()
    }

    /** Apply both endpoints before calculating, avoiding previews with half of a saved route. */
    fun openBookmark(route: SavedRoute): Boolean {
        if (engine.state.active || _ui.value.startingNavigation) return false
        val start = (route.origin as? BookmarkOrigin.Fixed)?.endpoint
        navigationPreferences.setTravelMode(route.mode)
        tripLog.write("travel_mode ${route.mode}")
        _ui.value = _ui.value.copy(
            manualStart = start?.point,
            manualStartLabel = start?.label,
            destination = route.destination.point,
            destinationLabel = route.destination.label,
            mapFocusRequest = route.destination.point,
            error = null,
        )
        planRoutePreview()
        return true
    }

    /** A library selection never replaces the endpoints of an active trip. */
    fun useBookmark(place: SavedPlace, asStart: Boolean): Boolean {
        if (engine.state.active || _ui.value.startingNavigation) return false
        if (asStart) setManualStart(place.endpoint.point, place.name) else setDestination(place.endpoint.point, place.name)
        _ui.value = _ui.value.copy(mapFocusRequest = place.endpoint.point)
        return true
    }

    /** Consume a camera request after the map becomes visible again. */
    fun clearMapFocusRequest() {
        _ui.value = _ui.value.copy(mapFocusRequest = null)
    }

    /** Calculate the route as soon as both endpoints are known, without starting navigation. */
    private fun planRoutePreview() {
        if (engine.state.active || _ui.value.startingNavigation) return
        val destination = _ui.value.destination
        val from = _ui.value.manualStart ?: currentPosition()
        routePreview.update(from, destination, travelMode.value) { result ->
            _ui.value = when (result) {
                PreviewResult.Cleared -> _ui.value.copy(previewRoute = null, planning = false)
                PreviewResult.Loading -> _ui.value.copy(previewRoute = null, planning = true, error = null)
                is PreviewResult.Ready -> _ui.value.copy(previewRoute = result.route, planning = false)
                is PreviewResult.Failed -> _ui.value.copy(previewRoute = null, planning = false, error = result.message)
            }
        }
    }

    /**
     * Plan a route from the best known start to the chosen destination and start navigating.
     * Routing runs in the background; the shared transaction starts [NavService] before publishing success.
     */
    fun startNavigation(onStarted: () -> Unit = {}) {
        if (engine.state.active || _ui.value.startingNavigation) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _ui.value = _ui.value.copy(error = context.getString(R.string.car_setup))
            return
        }
        val dest = _ui.value.destination ?: return
        // A start the user placed by hand wins over a coarse automatic fix.
        val planningFix = PlanningPosition.select(SystemClock.elapsedRealtime(), hub.lastGood, hub.lastNet)
        val from = _ui.value.manualStart ?: planningFix?.point ?: run {
            _ui.value = _ui.value.copy(error = context.getString(R.string.error_no_position))
            return
        }
        // How far the start could be off: hand-placed crosshair ~100 m, else the fix's own accuracy.
        val startAccuracy = when {
            _ui.value.manualStart != null -> MANUAL_START_ACCURACY_M
            planningFix?.source == FixSource.GPS -> planningFix.accuracyM?.toDouble() ?: 20.0
            else -> planningFix?.accuracyM?.toDouble() ?: 500.0
        }
        routePreview.cancel()
        val mode = travelMode.value
        _ui.value = _ui.value.copy(planning = true, startingNavigation = true, error = null)
        navigationWork.launch(onFailure = {
            tripLog.write("navigation_start_failed ${it.message}")
            runCatching { applyPower() }.onFailure { failure -> tripLog.write("navigation_cleanup_failed ${failure.message}") }
            _ui.value = _ui.value.copy(
                planning = false,
                startingNavigation = false,
                error = if (it is SecurityException) context.getString(R.string.car_setup) else it.message,
            )
        }, onFinished = {
            _ui.value = _ui.value.copy(planning = false, startingNavigation = false)
        }) {
            if (!sensors.checkLocationEnabled()) {
                ensureCurrent()
                _ui.value = _ui.value.copy(planning = false, startingNavigation = false, error = context.getString(R.string.error_location_off))
                return@launch
            }
            ensureCurrent()
            val request = RoutePreviewRequest(from, dest, mode)
            val route = routePreview.reusableRoute(_ui.value.previewRoute, request) ?: router.route(from, dest, mode = mode)
            ensureCurrent()
            withPreparedResource(create = { NativeRouteGeometry.create(route) }) { prepared ->
                ensureCurrent()
                navigationSession.start(prepared, route, dest, startAccuracy, mode, initialSpeedAt = { now ->
                    planningFix?.takeIf { it.source == FixSource.GPS && _ui.value.manualStart == null && now - it.elapsedMs in 0..START_SPEED_MAX_AGE_MS }?.speedMps?.toDouble()
                        ?: 0.0
                }, selectedEstimator = navigationEstimator.value)
            }
            routePreview.clear()
            _ui.value = _ui.value.copy(planning = false, startingNavigation = false, previewRoute = null)
            refresh()
            // These optional effects do not own the startup transaction.
            runCatching { offlineMap.saveCorridor(route, darkTheme()) }.onFailure { tripLog.write("corridor_save_failed ${it.message}") }
            runCatching(onStarted).onFailure { tripLog.write("navigation_callback_failed ${it.message}") }
        }
    }

    /** Foreground location can be rejected after the asynchronous service launch on recent Android. */
    fun onNavigationServiceFailure() {
        stopNavigation()
        _ui.value = _ui.value.copy(error = context.getString(R.string.car_setup))
    }

    /** End the trip: save it to the history and stop the engine. */
    fun stopNavigation() {
        navigationWork.cancel()
        if (_ui.value.startingNavigation) _ui.value = _ui.value.copy(startingNavigation = false, planning = false)
        if (!engine.state.active) return
        navigationSession.stop()
        updateDisplays()
        refresh()
    }

    /** Debug: drive as if GPS were jammed (to test dead reckoning with a working GPS). */
    fun setSimulateGpsLoss(on: Boolean) {
        engine.simulateGpsLoss = on
        tripLog.write("simulate_gps_loss=$on")
        refresh()
    }

    /** One engine step; called every [NavigationEngine.TICK_MS] by the service. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val positioning = hub.snapshot(now)
        engine.tick(now, positioning)
        if (engine.estimator == NavigationEstimator.KOTLIN) {
            nativeEstimator.tick(now, engine.state, positioning, ignoreGps = engine.simulateGpsLoss, motion = engine.motionEvidence(now), turn = engine.turnEvidence(now))
        }
        trips.onTick(now)
        tickCount++
        if (tickCount % 10 == 0L) logInertialShadow(now)
        // Battery level / charger / battery saver change slowly: re-check AUTO once a minute.
        if (tickCount % 120 == 0L) applyPower()
        val p = _powerProfile.value
        if (uiVisible && tickCount % p.uiEveryTicks == 0L) {
            refresh()
        } else if (tickCount % 10 == 0L) {
            refresh() // keep learning and the notification data fresh
        }
    }

    /** Low-rate diagnostics only: the experiment never changes the map marker, route or prompts. */
    private fun logInertialShadow(nowMs: Long) {
        val shadow = inertialShadow ?: return
        val estimate = shadow.estimate(nowMs * 1_000_000L)
        val status = if (estimate == null) "waiting_or_stale" else "tracking"
        tripLog.write(
            "eskf_shadow status=$status gps=${shadow.acceptedGps} rejected=${shadow.rejectedInputs} resets=${shadow.resets}" +
                (estimate?.let { " time_ns=${it.state.timestampNs} lat=${it.point.lat} lon=${it.point.lon} sigma_m=${it.horizontalSigmaM}" } ?: ""),
        )
    }

    /** Rebuild [ui] from the current state of all components. */
    fun refresh() {
        val hadTrustedPosition = _ui.value.hasTrustedPosition
        cells.refresh()
        val now = SystemClock.elapsedRealtime()
        hub.updateGpsState(now)
        val planningFix = PlanningPosition.select(now, hub.lastGood, hub.lastNet)
        hub.lastGood?.let { mapStart.rememberTrusted(it.point) }
        if (!uiVisible) {
            _ui.value = _ui.value.copy(guidance = engine.state)
            return
        }
        _ui.value = _ui.value.copy(
            cells = cells.status.value,
            guidance = engine.state,
            gpsState = hub.gpsState,
            lastVerdict = hub.lastJudged?.verdict,
            gnss = hub.gnss,
            jammed = hub.jammed,
            currentPosition = if (engine.state.active) engine.state.position else _ui.value.manualStart ?: planningFix?.point,
            hasTrustedPosition = planningFix != null,
            trustedAccuracyM = planningFix?.accuracyM?.toDouble(),
            trustedFromGps = planningFix?.source == FixSource.GPS,
            gpsRejectReasons = hub.lastJudged?.takeIf { it.verdict.level == TrustLevel.BAD }?.verdict?.reasons.orEmpty(),
            simulateGpsLoss = engine.simulateGpsLoss,
            sensorWarning = sensors.sensorWarning,
            locationEnabled = sensors.locationEnabled,
            log = tripLog.recent(30),
        )
        if (!hadTrustedPosition && _ui.value.hasTrustedPosition && _ui.value.destination != null && _ui.value.manualStart == null && _ui.value.previewRoute == null) {
            planRoutePreview()
        }
    }

    init {
        trips.onDiagnosticStart = { id ->
            diagnostics.startTrip(
                id,
                JSONObject()
                    .put("android_api", Build.VERSION.SDK_INT)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("travel_mode", engine.mode.name)
                    .put("navigation_method", navigationMethod.value.name)
                    .put("estimator", engine.estimator.name)
                    .put("power_mode", powerMode.value.name),
            )
        }
        trips.onDiagnosticEvent = diagnostics::onEvent
        trips.onProfileEvent = profileCapture::onEvent
        trips.onDiagnosticEnd = diagnostics::endTrip
        tripLog.onDiagnosticLine = diagnostics::onLog
        tripLog.onProfileLine = profileCapture::onLog
        // The process was killed mid-trip (or the system restarted the sticky service): resume navigation.
        if (trips.restore()) {
            tripLog.startTrip()
            engine.route?.let { route ->
                navigationWork.launch(onFailure = {
                    nativeEstimator.close()
                    tripLog.write("native_estimator_restore_failed ${it.message}")
                    if (engine.estimator == NavigationEstimator.NATIVE_KALMAN) _ui.value = _ui.value.copy(error = it.message)
                }) {
                    withPreparedResource(create = { NativeRouteGeometry.create(route) }) { prepared ->
                        ensureCurrent()
                        if (engine.state.active && engine.route === route) {
                            nativeEstimator.start(
                                prepared.value,
                                engine.progressS,
                                engine.state.speedKmh.toDouble() / 3.6,
                                engine.state.uncertaintyM,
                                engine.mode,
                                SystemClock.elapsedRealtime(),
                            )
                            nativeRouteProjector.install(route, prepared.value)
                            prepared.transfer()
                        }
                    }
                }
            }
            if (engine.mode == TravelMode.CAR) obd.start()
            runCatching { NavService.start(context) }.onFailure { tripLog.write("nav_service_restart_failed ${it.message}") }
        }
    }
}

private const val MANUAL_START_ACCURACY_M = 100.0
private const val START_SPEED_MAX_AGE_MS = 2_500L
