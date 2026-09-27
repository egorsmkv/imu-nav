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
import org.blinddriver.app.routing.OsrmRouter
import org.blinddriver.app.routing.Router
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
    val planning: Boolean = false,
    val error: String? = null,
    val simulateGpsLoss: Boolean = false,
    val log: List<String> = emptyList(),
)

/** Process-wide object graph. All engine access happens on the main thread. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tripLog = TripLog(context)
    private val voice = Voice(context)
    private val router: Router = OsrmRouter()

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

    /** Best current position for planning: good GPS, else network, else nothing. */
    fun currentPosition(): GeoPoint? = hub.lastGood?.point ?: hub.lastNet?.point ?: hub.lastFused?.point

    fun setDestination(p: GeoPoint) {
        _ui.value = _ui.value.copy(destination = p, error = null)
    }

    fun startNavigation(onStarted: () -> Unit) {
        val dest = _ui.value.destination ?: return
        val trusted = currentPosition()
        // Last resort: an untrusted GPS fix. A spoofed start only yields a visibly wrong route.
        val from = trusted ?: hub.lastJudged?.fix?.point ?: run {
            _ui.value = _ui.value.copy(error = "No position yet — wait for GPS or network")
            return
        }
        val warning = if (trusted == null) "Planned from an untrusted GPS fix (${hub.lastJudged?.verdict?.reasons?.joinToString()}) — check the start point" else null
        _ui.value = _ui.value.copy(planning = true, error = warning)
        scope.launch {
            runCatching { router.route(from, dest) }
                .onSuccess { route ->
                    tripLog.startTrip()
                    engine.start(route, dest, nowMs = SystemClock.elapsedRealtime())
                    _ui.value = _ui.value.copy(planning = false)
                    onStarted()
                }
                .onFailure { _ui.value = _ui.value.copy(planning = false, error = it.message) }
        }
    }

    fun stopNavigation() {
        engine.stop()
        tripLog.endTrip()
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
        _ui.value = _ui.value.copy(
            guidance = engine.state,
            gpsState = hub.gpsState,
            lastVerdict = hub.lastJudged?.verdict,
            gnss = hub.gnss,
            jammed = hub.jammed,
            currentPosition = engine.state.position ?: currentPosition(),
            simulateGpsLoss = engine.simulateGpsLoss,
            log = tripLog.recent.takeLast(30),
        )
    }
}

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
