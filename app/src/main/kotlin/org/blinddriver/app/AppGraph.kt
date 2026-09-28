package org.blinddriver.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blinddriver.app.cells.CellManager
import org.blinddriver.app.cells.CellStatus
import org.blinddriver.app.routing.OsrmRouter
import org.blinddriver.app.routing.Router
import org.blinddriver.app.routing.OfflineRouting
import org.blinddriver.app.routing.SmartRouter
import org.blinddriver.app.sensors.SensorHub
import org.blinddriver.app.voice.Voice
import org.blinddriver.core.Tuning
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.ServiceArea
import org.blinddriver.core.gnss.GnssSnapshot
import org.blinddriver.core.gnss.GpsState
import org.blinddriver.core.gnss.PositioningHub
import org.blinddriver.core.gnss.Verdict
import org.blinddriver.core.nav.GuidanceState
import org.blinddriver.core.nav.NavListener
import org.blinddriver.core.nav.NavigationEngine
import org.blinddriver.core.speed.SpeedProfile
import org.blinddriver.core.speed.SpeedProfileStore

/** Everything the UI shows, refreshed every engine tick. */
data class UiState(
    val guidance: GuidanceState = GuidanceState(),
    val gpsState: GpsState = GpsState.LOST,
    val lastVerdict: Verdict? = null,
    val gnss: GnssSnapshot = GnssSnapshot(),
    val jammed: Boolean = false,
    val currentPosition: GeoPoint? = null,
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
    val planning: Boolean = false,
    val error: String? = null,
    val simulateGpsLoss: Boolean = false,
    val sensorWarning: String? = null,
    /** System-wide Location switch; when off, Android delivers no fixes to any app. */
    val locationEnabled: Boolean = true,
    val log: List<String> = emptyList(),
    val cells: CellStatus = CellStatus(),
)


/** Process-wide object graph. All engine access happens on the main thread. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tripLog = TripLog(context)
    /** UI and voice follow the phone's language: Ukrainian on Ukrainian phones, English otherwise. */
    private val ukrainian = java.util.Locale.getDefault().language == "uk"
    private val voice = Voice(context, if (ukrainian) java.util.Locale.forLanguageTag("uk-UA") else java.util.Locale.getDefault())
    /** Offline GraphHopper pack first; OSRM online only as an allowed fallback. */
    val offlineRouting = OfflineRouting(context, scope, tripLog::write)
    private val router: Router = SmartRouter(offlineRouting, OsrmRouter(), tripLog::write) { context.getString(R.string.routing_no_coverage) }

    val tuning = MutableStateFlow(Tuning.DEFAULT)

    /** Fixes outside this area are treated as spoofed. Set to [ServiceArea.EVERYWHERE] to use the app elsewhere. */
    val hub = PositioningHub(area = ServiceArea.UKRAINE_COARSE).also { it.log = tripLog::write }

    private val listener = object : NavListener {
        override fun onSay(text: String, urgent: Boolean) = voice.speak(text, urgent)
        override fun onLog(message: String) = tripLog.write(message)
        override fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {
            scope.launch {
                runCatching { router.route(from, destination, via) }
                    .onSuccess { engine.setRoute(it, SystemClock.elapsedRealtime()) }
                    .onFailure {
                        tripLog.write("reroute_failed ${it.message}")
                        engine.rerouteFailed()
                        _ui.value = _ui.value.copy(error = it.message)
                    }
            }
        }
    }

    val engine: NavigationEngine = NavigationEngine(
        tuning = { tuning.value },
        speedProfile = SpeedProfile(PrefsSpeedProfileStore(context)),
        phrases = if (ukrainian) org.blinddriver.core.nav.UkrainianPhrases else org.blinddriver.core.nav.EnglishPhrases,
        listener = listener,
    )

    val sensors = SensorHub(
        context,
        hub,
        onImu = { engine.onImu(it, hub.gyroBias.biasDegS) },
        log = tripLog::write,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---------------------------------------------------------------- offline cell positioning

    val cells = CellManager(context, scope, hub, tripLog::write)

    fun startSensing() {
        sensors.start()
        cells.scanner.start()
    }

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

    fun setManualStart(p: GeoPoint?) {
        tripLog.write("manual_start ${p?.let { "%.5f %.5f".format(it.lat, it.lon) }}")
        _ui.value = _ui.value.copy(manualStart = p, error = null)
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun setDestination(p: GeoPoint?) {
        _ui.value = _ui.value.copy(destination = p, error = null)
    }

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
            runCatching { router.route(from, dest) }
                .onSuccess { route ->
                    tripLog.startTrip()
                    tripLog.write("start_accuracy=${startAccuracy.toInt()}")
                    engine.start(route, dest, nowMs = SystemClock.elapsedRealtime(), startAccuracyM = startAccuracy)
                    _ui.value = _ui.value.copy(planning = false)
                    onStarted()
                }
                .onFailure { _ui.value = _ui.value.copy(planning = false, error = it.message) }
        }
    }

    fun stopNavigation() {
        engine.stop()
        tripLog.endTrip()
        cells.maybeAutoSync()
        refresh()
    }

    fun setSimulateGpsLoss(on: Boolean) {
        engine.simulateGpsLoss = on
        tripLog.write("simulate_gps_loss=$on")
        refresh()
    }

    /** One engine step; called every [NavigationEngine.TICK_MS] by the service. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        engine.tick(now, hub.snapshot(now))
        refresh()
    }

    fun refresh() {
        cells.refresh()
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
            gpsRejectReasons = hub.lastJudged?.takeIf { it.verdict.level == org.blinddriver.core.gnss.TrustLevel.BAD }?.verdict?.reasons.orEmpty(),
            simulateGpsLoss = engine.simulateGpsLoss,
            sensorWarning = sensors.sensorWarning,
            locationEnabled = sensors.locationEnabled,
            log = tripLog.recent.takeLast(30),
        )
    }
}

private const val MANUAL_START_ACCURACY_M = 100.0

private class PrefsSpeedProfileStore(context: Context) : SpeedProfileStore {
    private val prefs = context.getSharedPreferences("speed_profile", Context.MODE_PRIVATE)

    override fun load() = SpeedProfile.State(
        cityRatio = prefs.getFloat("city_ratio", 0.8f).toDouble(),
        highwayRatio = prefs.getFloat("hwy_ratio", 0.8f).toDouble(),
        cityN = prefs.getInt("city_n", 0),
        highwayN = prefs.getInt("hwy_n", 0),
    )

    override fun save(state: SpeedProfile.State) {
        prefs.edit()
            .putFloat("city_ratio", state.cityRatio.toFloat())
            .putFloat("hwy_ratio", state.highwayRatio.toFloat())
            .putInt("city_n", state.cityN)
            .putInt("hwy_n", state.highwayN)
            .apply()
    }
}
