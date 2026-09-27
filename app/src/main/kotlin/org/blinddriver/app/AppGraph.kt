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
import org.blinddriver.app.cells.CellDatabase
import org.blinddriver.app.cells.CellScanner
import org.blinddriver.app.cells.OpenCellIdDownloader
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
    /** Start point chosen by the user when no trusted position exists (GPS spoofed/jammed, no network). */
    val manualStart: GeoPoint? = null,
    val hasTrustedPosition: Boolean = false,
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

/** Offline cell positioning status for the UI. */
data class CellStatus(
    val seen: Int = 0,
    val located: Int = 0,
    val accuracyM: Double? = null,
    val imported: Long = 0,
    val learned: Long = 0,
    val learning: Boolean = true,
    val hasToken: Boolean = false,
    /** Import / download progress or result message. */
    val busy: String? = null,
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

    // ---------------------------------------------------------------- offline cell positioning

    private val cellPrefs = context.getSharedPreferences("cells", Context.MODE_PRIVATE)
    val cellDb = CellDatabase(context)
    private var lastCellLogMs = 0L
    private var lastLearnedFixMs = -1L
    private var cellCounts = CellDatabase.Counts(0, 0)

    val cellScanner = CellScanner(
        context,
        cellDb,
        onFix = { raw, fix ->
            hub.onFix(raw)
            if (raw.elapsedMs - lastCellLogMs >= 30_000) {
                lastCellLogMs = raw.elapsedMs
                tripLog.write("cell_fix towers=${fix.towersUsed}/${fix.towersSeen} acc=${fix.accuracyM.toInt()} %.5f %.5f".format(fix.lat, fix.lon))
            }
        },
        log = tripLog::write,
    )

    init {
        scope.launch { reloadCellCounts() }
    }

    fun startSensing() {
        sensors.start()
        cellScanner.start()
    }

    fun stopSensing() {
        sensors.stop()
        cellScanner.stop()
    }

    private suspend fun reloadCellCounts() {
        cellCounts = withContext(Dispatchers.IO) { cellDb.counts() }
        refresh()
    }

    private fun setCellBusy(msg: String?) {
        _ui.value = _ui.value.copy(cells = _ui.value.cells.copy(busy = msg))
    }

    /** Import an OpenCellID CSV / CSV.GZ picked by the user. */
    fun importCells(open: () -> java.io.InputStream?) {
        scope.launch {
            setCellBusy("Importing…")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val input = open() ?: error("cannot open file")
                    input.use { cellDb.importOpenCellId(it) { n -> scope.launch { setCellBusy("Importing… $n towers") } } }
                }
            }
            result.onSuccess { tripLog.write("cells_imported n=$it") }.onFailure { tripLog.write("cells_import_failed ${it.message}") }
            reloadCellCounts()
            setCellBusy(result.fold({ "Imported $it towers" }, { "Import failed: ${it.message}" }))
        }
    }

    /** Download and import the OpenCellID export for [mcc] (255 = Ukraine) using the user's token. */
    fun downloadCells(token: String, mcc: Int = 255) {
        if (token.isBlank()) return
        cellPrefs.edit().putString("token", token.trim()).apply()
        scope.launch {
            setCellBusy("Downloading…")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = java.io.File(context.cacheDir, "ocid-$mcc.csv.gz")
                    OpenCellIdDownloader.download(token, mcc, file) { b -> scope.launch { setCellBusy("Downloading… ${b / 1024} KB") } }
                    scope.launch { setCellBusy("Importing…") }
                    val n = file.inputStream().use { cellDb.importOpenCellId(it) { n -> scope.launch { setCellBusy("Importing… $n towers") } } }
                    file.delete()
                    n
                }
            }
            result.onSuccess { tripLog.write("cells_downloaded mcc=$mcc n=$it") }.onFailure { tripLog.write("cells_download_failed ${it.message}") }
            reloadCellCounts()
            setCellBusy(result.fold({ "Downloaded and imported $it towers" }, { "Download failed: ${it.message}" }))
        }
    }

    fun savedCellToken(): String = cellPrefs.getString("token", "").orEmpty()

    fun setCellLearning(on: Boolean) {
        cellPrefs.edit().putBoolean("learning", on).apply()
        refresh()
    }

    /** Record where visible cells are whenever we have a fresh, accurate GOOD GPS fix. */
    private fun maybeLearnCells() {
        if (!cellPrefs.getBoolean("learning", true)) return
        val good = hub.lastGood ?: return
        if (good.elapsedMs == lastLearnedFixMs) return
        val acc = good.accuracyM ?: return
        val obs = cellScanner.lastObservations
        if (acc > 30f || obs.isEmpty() || kotlin.math.abs(cellScanner.lastScanMs - good.elapsedMs) > 10_000) return
        // One sample per ~20 s is plenty and keeps database writes low.
        if (lastLearnedFixMs > 0 && good.elapsedMs - lastLearnedFixMs < 20_000) return
        lastLearnedFixMs = good.elapsedMs
        scope.launch {
            withContext(Dispatchers.IO) { cellDb.learn(obs.map { it.key }, good.lat, good.lon, acc.toDouble()) }
            reloadCellCounts()
        }
    }

    /**
     * Best trusted position for planning: a GOOD GPS fix, else a network fix. The fused provider is
     * deliberately ignored — Android builds it largely from GPS, so it inherits spoofed positions.
     */
    fun currentPosition(): GeoPoint? = hub.lastGood?.point ?: hub.lastNet?.point

    fun setManualStart(p: GeoPoint?) {
        tripLog.write("manual_start ${p?.let { "%.5f %.5f".format(it.lat, it.lon) }}")
        _ui.value = _ui.value.copy(manualStart = p, error = null)
    }

    fun setDestination(p: GeoPoint) {
        _ui.value = _ui.value.copy(destination = p, error = null)
    }

    fun startNavigation(onStarted: () -> Unit) {
        val dest = _ui.value.destination ?: return
        if (!sensors.locationEnabled) {
            _ui.value = _ui.value.copy(error = "Location is turned off on this phone — turn it on in system settings")
            return
        }
        val from = currentPosition() ?: _ui.value.manualStart ?: run {
            val why = hub.lastJudged?.takeIf { it.verdict.level == org.blinddriver.core.gnss.TrustLevel.BAD }?.verdict?.reasons
                ?.let { "GPS is rejected (${it.joinToString()})" } ?: "No GPS fix yet"
            _ui.value = _ui.value.copy(error = "$why and there is no network location. Put the crosshair on your position and tap “Start here”.")
            return
        }
        _ui.value = _ui.value.copy(planning = true, error = null)
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
        maybeLearnCells()
        val cellFix = cellScanner.lastFix
        _ui.value = _ui.value.copy(
            cells = _ui.value.cells.copy(
                seen = cellScanner.lastObservations.size,
                located = cellFix?.towersUsed ?: 0,
                accuracyM = cellFix?.accuracyM,
                imported = cellCounts.imported,
                learned = cellCounts.learned,
                learning = cellPrefs.getBoolean("learning", true),
                hasToken = savedCellToken().isNotBlank(),
            ),
            guidance = engine.state,
            gpsState = hub.gpsState,
            lastVerdict = hub.lastJudged?.verdict,
            gnss = hub.gnss,
            jammed = hub.jammed,
            currentPosition = engine.state.position ?: currentPosition() ?: _ui.value.manualStart,
            hasTrustedPosition = currentPosition() != null,
            gpsRejectReasons = hub.lastJudged?.takeIf { it.verdict.level == org.blinddriver.core.gnss.TrustLevel.BAD }?.verdict?.reasons.orEmpty(),
            simulateGpsLoss = engine.simulateGpsLoss,
            sensorWarning = sensors.sensorWarning,
            locationEnabled = sensors.locationEnabled,
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
