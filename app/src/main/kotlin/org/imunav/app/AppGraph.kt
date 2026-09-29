package org.imunav.app

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.cells.CellManager
import org.imunav.app.cells.CellStatus
import org.imunav.app.maps.OfflineMap
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
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.GnssSnapshot
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.gnss.Verdict
import org.imunav.core.nav.EnglishPhrases
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.nav.RussianPhrases
import org.imunav.core.nav.UkrainianPhrases
import org.imunav.core.route.TravelMode
import org.imunav.core.speed.SpeedProfile
import org.imunav.core.speed.SpeedProfileStore
import java.util.Locale

/**
 * Everything the UI shows, as one immutable value. [AppGraph] publishes a new copy (via a
 * StateFlow) every engine tick; Compose redraws only the parts that changed.
 */
data class UiState(
    /** Navigation state from the engine (route, next maneuver, position on the route…). */
    val guidance: GuidanceState = GuidanceState(),
    val gpsState: GpsState = GpsState.LOST,
    /** The classifier's verdict on the latest GPS fix (for the diagnostics sheet). */
    val lastVerdict: Verdict? = null,
    val gnss: GnssSnapshot = GnssSnapshot(),
    val jammed: Boolean = false,
    /** Where to draw the position dot (engine estimate, else best trusted fix, else the manual start). */
    val currentPosition: GeoPoint? = null,
    /** Destination picked on the map or in search, before navigation starts. */
    val destination: GeoPoint? = null,
    /** Start point chosen by the user when no trusted position exists (GPS spoofed/jammed, no network). */
    val manualStart: GeoPoint? = null,
    val hasTrustedPosition: Boolean = false,
    /** Accuracy of the best trusted position, metres (null = none). */
    val trustedAccuracyM: Double? = null,
    /** The trusted position comes from a GOOD GPS fix (else from cell/network). */
    val trustedFromGps: Boolean = false,
    /** Why GPS fixes are currently rejected, for the "no trusted position" message. */
    val gpsRejectReasons: List<String> = emptyList(),
    /** A route is being computed. */
    val planning: Boolean = false,
    /** A message to show once in a snackbar (then cleared with [AppGraph.clearError]). */
    val error: String? = null,
    /** Debug switch: pretend GPS is jammed. */
    val simulateGpsLoss: Boolean = false,
    /** The phone lacks some sensors (e.g. no gyroscope); shown in diagnostics. */
    val sensorWarning: String? = null,
    /** System-wide Location switch; when off, Android delivers no fixes to any app. */
    val locationEnabled: Boolean = true,
    /** The latest trip-log lines. */
    val log: List<String> = emptyList(),
    val cells: CellStatus = CellStatus(),
)

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
    /** Coroutine scope for the app's lifetime; runs on the main thread unless told otherwise. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tripLog = TripLog(context)

    /** UI and voice language: the in-app choice, or the phone's language by default. */
    private val voicePrefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    val voiceEnabled = MutableStateFlow(voicePrefs.getBoolean("enabled", true))
    private val voice = Voice(context, AppLanguage.locale(AppLanguage.get(context)), voiceEnabled.value)
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
    val search = PlaceSearch(context, { offlineRouting.searchDb }, { offlineRouting.status.value.allowOnline })
    private val router: Router = SmartRouter(
        offlineRouting,
        OsrmRouter(),
        tripLog::write,
        noOfflineMessage = { context.getString(R.string.routing_no_coverage) },
        noWalkingMessage = { context.getString(R.string.routing_no_walking) },
    )

    // ---------------------------------------------------------------- travel mode

    private val modePrefs = context.getSharedPreferences("travel", Context.MODE_PRIVATE)

    /** Car or on foot, chosen before starting (remembered between app starts). */
    val travelMode = MutableStateFlow(TravelMode.entries.firstOrNull { it.name == modePrefs.getString("mode", null) } ?: TravelMode.CAR)

    fun setTravelMode(mode: TravelMode) {
        modePrefs.edit { putString("mode", mode.name) }
        travelMode.value = mode
        tripLog.write("travel_mode $mode")
    }

    /** Fallback used when GPS is unavailable; hybrid preserves the original app behaviour. */
    val navigationMethod = MutableStateFlow(
        NavigationMethod.entries.firstOrNull { it.name == modePrefs.getString("navigation_method", null) } ?: NavigationMethod.HYBRID,
    )

    fun setNavigationMethod(method: NavigationMethod) {
        modePrefs.edit { putString("navigation_method", method.name) }
        navigationMethod.value = method
        tripLog.write("navigation_method $method")
    }

    /** Engine thresholds (factory defaults; see [Tuning]). */
    val tuning = MutableStateFlow(Tuning.DEFAULT)

    /** Fixes outside this area are treated as spoofed. Set to [ServiceArea.EVERYWHERE] to use the app elsewhere. */
    val serviceArea: ServiceArea = ServiceArea.UKRAINE_COARSE
    val hub = PositioningHub(area = serviceArea).also { it.log = tripLog::write }

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
        override fun onLog(message: String) = tripLog.write(message)
        override fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {
            scope.launch {
                runCatching { router.route(from, destination, via, engine.mode) }
                    .onSuccess {
                        engine.setRoute(it, SystemClock.elapsedRealtime())
                        trips.onRoute(it)
                        offlineMap.saveCorridor(it, darkTheme())
                    }
                    .onFailure {
                        tripLog.write("reroute_failed ${it.message}")
                        engine.rerouteFailed()
                        _ui.value = _ui.value.copy(error = it.message)
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
    )

    // Kotlin convention: a private mutable flow (_ui) and a public read-only view (ui) of it.
    private val _ui = MutableStateFlow(UiState())

    /** What the UI shows; collect it with `collectAsStateWithLifecycle()`. */
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---------------------------------------------------------------- offline cell positioning

    /** Offline cell-tower positioning: tower database, scanning, downloads, sharing. */
    val cells = CellManager(context, scope, hub, tripLog::write)

    // ---------------------------------------------------------------- power

    /** Battery trade-offs (Settings → Battery). */
    val power = PowerPolicy(context)
    private val _powerProfile = MutableStateFlow(power.resolve())

    /** The profile in effect (AUTO already resolved). */
    val powerProfile: StateFlow<PowerProfile> = _powerProfile.asStateFlow()
    val powerMode = MutableStateFlow(power.mode)
    val keepScreenOn = MutableStateFlow(power.keepScreenOn)

    /** True while an activity shows the app; UI state is not rebuilt for an invisible screen. */
    @Volatile var uiVisible = false
    private var tickCount = 0L

    init {
        cells.scanner.intervalMs = {
            val p = _powerProfile.value
            when {
                !engine.state.active -> if (hub.lastGood != null) p.cellScanIdleMs else p.cellScanNoGpsMs
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
        sensors.configure(p, navigating = engine.state.active, walking = engine.state.active && engine.mode == TravelMode.FOOT)
    }

    /** Start GPS, sensors and cell scans (when the app is visible or navigating). */
    fun startSensing() {
        applyPower()
        sensors.start()
        cells.scanner.start()
    }

    /** Stop them again to save battery. */
    fun stopSensing() {
        sensors.stop()
        cells.scanner.stop()
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Best trusted position for planning: a GOOD GPS fix, else a network/cell fix. The fused provider is
     * deliberately ignored — Android builds it largely from GPS, so it inherits spoofed positions.
     */
    fun currentPosition(): GeoPoint? = hub.lastGood?.point ?: hub.lastNet?.point

    /** The user placed the start by hand ("Start here") because no trusted position exists. */
    fun setManualStart(p: GeoPoint?) {
        tripLog.write("manual_start ${p?.let { "%.5f %.5f".format(Locale.US, it.lat, it.lon) }}")
        _ui.value = _ui.value.copy(manualStart = p, error = null)
    }

    /** The error message was shown; forget it. */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** Destination picked on the map / in search (null clears it). */
    fun setDestination(p: GeoPoint?) {
        _ui.value = _ui.value.copy(destination = p, error = null)
    }

    /**
     * Plan a route from the best known start to the chosen destination and start navigating.
     * Routing runs in the background; [onStarted] is called on success (the UI then starts [NavService]).
     */
    fun startNavigation(onStarted: () -> Unit) {
        val dest = _ui.value.destination ?: return
        if (!sensors.locationEnabled) {
            _ui.value = _ui.value.copy(error = context.getString(R.string.error_location_off))
            return
        }
        // A start the user placed by hand wins over a coarse automatic fix.
        val from = _ui.value.manualStart ?: currentPosition() ?: run {
            _ui.value = _ui.value.copy(error = context.getString(R.string.error_no_position))
            return
        }
        // How far the start could be off: hand-placed crosshair ~100 m, else the fix's own accuracy.
        val startAccuracy = when {
            _ui.value.manualStart != null -> MANUAL_START_ACCURACY_M
            hub.lastGood != null -> hub.lastGood?.accuracyM?.toDouble() ?: 20.0
            else -> hub.lastNet?.accuracyM?.toDouble() ?: 500.0
        }
        _ui.value = _ui.value.copy(planning = true, error = null)
        scope.launch {
            val mode = travelMode.value
            runCatching { router.route(from, dest, mode = mode) }
                .onSuccess { route ->
                    tripLog.startTrip()
                    tripLog.write("start_accuracy=${startAccuracy.toInt()}")
                    engine.start(route, dest, nowMs = SystemClock.elapsedRealtime(), startAccuracyM = startAccuracy, mode = mode)
                    trips.begin(route, dest, emptyList(), startAccuracy, mode)
                    offlineMap.saveCorridor(route, darkTheme())
                    applyPower()
                    _ui.value = _ui.value.copy(planning = false)
                    onStarted()
                }
                .onFailure { _ui.value = _ui.value.copy(planning = false, error = it.message) }
        }
    }

    /** End the trip: save it to the history and stop the engine. */
    fun stopNavigation() {
        trips.end(arrived = engine.state.arrived)
        engine.stop()
        applyPower()
        tripLog.endTrip()
        cells.maybeAutoSync()
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
        engine.tick(now, hub.snapshot(now))
        trips.onTick(now)
        tickCount++
        // Battery level / charger / battery saver change slowly: re-check AUTO once a minute.
        if (tickCount % 120 == 0L) applyPower()
        val p = _powerProfile.value
        if (uiVisible && tickCount % p.uiEveryTicks == 0L) {
            refresh()
        } else if (tickCount % 10 == 0L) {
            refresh() // keep learning and the notification data fresh
        }
    }

    /** Rebuild [ui] from the current state of all components. */
    fun refresh() {
        cells.refresh()
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
            currentPosition = engine.state.position ?: currentPosition() ?: _ui.value.manualStart,
            hasTrustedPosition = currentPosition() != null,
            trustedAccuracyM = (hub.lastGood ?: hub.lastNet)?.accuracyM?.toDouble(),
            trustedFromGps = hub.lastGood != null,
            gpsRejectReasons = hub.lastJudged?.takeIf { it.verdict.level == TrustLevel.BAD }?.verdict?.reasons.orEmpty(),
            simulateGpsLoss = engine.simulateGpsLoss,
            sensorWarning = sensors.sensorWarning,
            locationEnabled = sensors.locationEnabled,
            log = tripLog.recent.takeLast(30),
        )
    }

    init {
        // The process was killed mid-trip (or the system restarted the sticky service): resume navigation.
        if (trips.restore()) {
            tripLog.startTrip()
            runCatching { NavService.start(context) }.onFailure { tripLog.write("nav_service_restart_failed ${it.message}") }
        }
    }
}

private const val MANUAL_START_ACCURACY_M = 100.0

/** Keeps the learned driving-speed profile in SharedPreferences. */
private class PrefsSpeedProfileStore(context: Context) : SpeedProfileStore {
    private val prefs = context.getSharedPreferences("speed_profile", Context.MODE_PRIVATE)

    override fun load() = SpeedProfile.State(
        cityRatio = prefs.getFloat("city_ratio", 0.8f).toDouble(),
        highwayRatio = prefs.getFloat("hwy_ratio", 0.8f).toDouble(),
        cityN = prefs.getInt("city_n", 0),
        highwayN = prefs.getInt("hwy_n", 0),
    )

    override fun save(state: SpeedProfile.State) {
        prefs.edit {
            putFloat("city_ratio", state.cityRatio.toFloat())
            putFloat("hwy_ratio", state.highwayRatio.toFloat())
            putInt("city_n", state.cityN)
            putInt("hwy_n", state.highwayN)
        }
    }
}
