package org.imunav.app.trips

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.ImuSample
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.record.RouteCodec
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripRecorder
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/** One finished trip, as listed in the history. */
data class TripSummary(
    val id: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val destination: GeoPoint?,
    val arrived: Boolean,
    /** Distance driven according to the engine, m. */
    val drivenM: Double,
    val durationS: Double,
    val movingS: Double,
    /** Seconds navigated without usable GPS, and the distance driven meanwhile. */
    val blindS: Double,
    val blindM: Double,
    val maxUncertaintyM: Double,
    val routeLengthM: Double,
    val reroutes: Int,
    val recording: String,
    /** Length of the drive snapped to roads (map matching), once computed. */
    val matchedLengthM: Double? = null,
    /** Driven or walked. Trips saved before walking support are car trips. */
    val mode: TravelMode = TravelMode.CAR,
) {
    val avgMovingKmh: Double get() = if (movingS > 0) drivenM / movingS * 3.6 else 0.0

    /** Serialize for the history index (one JSON object per line). */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("start", startWallMs).put("end", endWallMs)
        .put("destLat", destination?.lat).put("destLon", destination?.lon)
        .put("arrived", arrived).put("driven", drivenM).put("duration", durationS).put("moving", movingS)
        .put("blindS", blindS).put("blindM", blindM).put("maxUnc", maxUncertaintyM).put("routeLen", routeLengthM)
        .put("reroutes", reroutes).put("recording", recording).put("matched", matchedLengthM).put("mode", mode.name)

    companion object {
        /** Parse a line of the history index. */
        fun fromJson(o: JSONObject) = TripSummary(
            id = o.getString("id"),
            startWallMs = o.getLong("start"),
            endWallMs = o.getLong("end"),
            destination = if (o.has("destLat") && !o.isNull("destLat")) GeoPoint(o.getDouble("destLat"), o.getDouble("destLon")) else null,
            arrived = o.optBoolean("arrived"),
            drivenM = o.optDouble("driven", 0.0),
            durationS = o.optDouble("duration", 0.0),
            movingS = o.optDouble("moving", 0.0),
            blindS = o.optDouble("blindS", 0.0),
            blindM = o.optDouble("blindM", 0.0),
            maxUncertaintyM = o.optDouble("maxUnc", 0.0),
            routeLengthM = o.optDouble("routeLen", 0.0),
            reroutes = o.optInt("reroutes"),
            recording = o.optString("recording"),
            matchedLengthM = if (o.has("matched") && !o.isNull("matched")) o.getDouble("matched") else null,
            mode = modeOf(o),
        )

        /** The "mode" field, defaulting to car for data written before walking support. */
        fun modeOf(o: JSONObject): TravelMode = TravelMode.entries.firstOrNull { it.name == o.optString("mode") } ?: TravelMode.CAR
    }
}

/** Sums over all saved trips, for the top of the History screen. */
data class HistoryTotals(val trips: Int, val drivenM: Double, val durationS: Double, val blindM: Double)

/**
 * Trip lifecycle: records raw inputs to `files/trips/<id>.rec.gz` while navigating, accumulates
 * stats, persists the active trip so it survives the app being killed, and keeps the history.
 */
class TripManager(private val context: Context, private val hub: PositioningHub, private val engine: NavigationEngine, private val log: (String) -> Unit) {
    private val dir = File(context.filesDir, "trips").apply { mkdirs() }
    private val index = File(dir, "index.jsonl")
    private val activeFile = File(dir, "active.json")

    private var recorder: TripRecorder? = null

    /** Recording and state files are written here, never on the main thread; one thread keeps event order. */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * [route] encoded once per route change (it can be large), reused by every [persist].
     * Only touched on the [io] thread: encoding a long route takes long enough to freeze the UI.
     */
    private var routeEncoded = ""

    @Volatile private var id: String? = null
    private var recordingName = ""
    private var startWall = 0L
    private var destination: GeoPoint? = null
    private var waypoints: List<GeoPoint> = emptyList()
    private var startAccuracy = 0.0
    private var mode = TravelMode.CAR
    private var route: Route? = null
    private var drivenM = 0.0
    private var movingS = 0.0
    private var blindS = 0.0
    private var blindM = 0.0
    private var maxUnc = 0.0
    private var reroutes = 0
    private var lastTickMs = -1L
    private var lastPersistMs = 0L

    private val _history = MutableStateFlow<List<TripSummary>>(emptyList())
    val history: StateFlow<List<TripSummary>> = _history.asStateFlow()

    val active: Boolean get() = id != null

    init {
        io.execute { _history.value = loadHistory() }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Navigation started: open a new recording and reset the statistics. */
    fun begin(route: Route, destination: GeoPoint, waypoints: List<GeoPoint>, startAccuracyM: Double, mode: TravelMode = TravelMode.CAR) {
        this.mode = mode
        end(arrived = false) // close anything left open
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        id = stamp
        recordingName = "trip-$stamp.rec.gz"
        startWall = System.currentTimeMillis()
        this.destination = destination
        this.waypoints = waypoints
        this.startAccuracy = startAccuracyM
        this.route = route
        encodeRoute(route)
        drivenM = 0.0
        movingS = 0.0
        blindS = 0.0
        blindM = 0.0
        maxUnc = 0.0
        reroutes = 0
        lastTickMs = -1L
        openRecorder(append = false)
        val now = SystemClock.elapsedRealtime()
        record(TripEvent.Start(now, destination, waypoints, startAccuracyM))
        record(TripEvent.Mode(now, mode))
        record(TripEvent.Estimator(now, engine.estimator))
        record(TripEvent.RouteSet(now, route))
        persist(force = true)
        log("trip_begin $recordingName")
    }

    /** A reroute happened: record the new route. */
    fun onRoute(route: Route) {
        if (!active) return
        this.route = route
        encodeRoute(route)
        reroutes++
        record(TripEvent.RouteSet(SystemClock.elapsedRealtime(), route))
        persist(force = true)
    }

    /** Record a step (walking trips). */
    fun onStep(elapsedMs: Long) {
        if (recorder != null) record(TripEvent.StepTaken(elapsedMs))
    }

    /** Record the car's speed from the OBD-II adapter. */
    fun onVehicleSpeed(kmh: Int, elapsedMs: Long) {
        if (recorder != null) record(TripEvent.VehicleSpeed(elapsedMs, kmh.toFloat()))
    }

    /** Record a barometer reading. */
    fun onPressure(hPa: Float, elapsedMs: Long) {
        if (recorder != null) record(TripEvent.Pressure(elapsedMs, hPa))
    }

    /** Record an IMU sample (only while a trip is being recorded). */
    fun onImu(sample: ImuSample) {
        if (recorder != null) record(TripEvent.Imu(sample))
    }

    /** Called every engine tick while navigating. */
    fun onTick(nowMs: Long) {
        if (!active) return
        val st = engine.state
        if (lastTickMs > 0) {
            val dt = ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 5.0)
            val ds = st.speedKmh / 3.6 * dt
            drivenM += ds
            if (st.speedKmh > 2f) movingS += dt
            if (!st.source.isGps) {
                blindS += dt
                blindM += ds
            }
        }
        lastTickMs = nowMs
        maxUnc = max(maxUnc, st.uncertaintyM)
        st.position?.let { record(TripEvent.Estimate(nowMs, it.lat, it.lon, st.s, st.uncertaintyM, st.source.label)) }
        persist(force = false)
    }

    /** Finish the trip: close the recording and add it to the history. */
    fun end(arrived: Boolean) {
        val tripId = id ?: return
        record(TripEvent.Stop(SystemClock.elapsedRealtime()))
        recorder?.let { r -> io.execute { r.close() } }
        recorder = null
        hub.recorder = null
        io.execute { activeFile.delete() }
        val end = System.currentTimeMillis()
        val summary = TripSummary(
            tripId, startWall, end, destination, arrived, drivenM, (end - startWall) / 1000.0, movingS, blindS, blindM,
            maxUnc, route?.length ?: 0.0, reroutes, recordingName, mode = mode,
        )
        id = null
        // Skip accidental starts (no movement at all) to keep the history meaningful.
        if (summary.drivenM < 50 && summary.durationS < 120) {
            val name = recordingName
            io.execute { File(dir, name).delete() }
            log("trip_discarded $tripId")
            return
        }
        io.execute {
            index.appendText(summary.toJson().toString() + "\n")
            _history.value = loadHistory()
        }
        log("trip_end $tripId driven=${drivenM.toInt()}m blind=${blindS.toInt()}s")
    }

    // ------------------------------------------------------------------ surviving process death

    /**
     * If the app was killed mid-trip, reopen the recording and put the engine back on the saved route
     * near the saved position. Returns true when a trip was restored.
     */
    fun restore(): Boolean {
        val o = runCatching { JSONObject(activeFile.readText()) }.getOrNull() ?: return false
        val savedAt = o.optLong("savedAt")
        val gapS = (System.currentTimeMillis() - savedAt) / 1000.0
        if (gapS < 0 || gapS > MAX_RESTORE_GAP_S) {
            activeFile.delete()
            return false
        }
        val restoredRoute = runCatching { RouteCodec.decode(o.getString("route")) }.getOrNull() ?: return false
        id = o.getString("id")
        recordingName = o.getString("recording")
        startWall = o.getLong("start")
        val restoredDestination = GeoPoint(o.getDouble("destLat"), o.getDouble("destLon"))
        destination = restoredDestination
        waypoints = o.optJSONArray("via")?.let { a -> (0 until a.length() step 2).map { GeoPoint(a.getDouble(it), a.getDouble(it + 1)) } }.orEmpty()
        startAccuracy = o.optDouble("startAcc", 0.0)
        mode = TripSummary.modeOf(o)
        route = restoredRoute
        val encoded = o.getString("route")
        io.execute { routeEncoded = encoded }
        drivenM = o.optDouble("driven")
        movingS = o.optDouble("moving")
        blindS = o.optDouble("blindS")
        blindM = o.optDouble("blindM")
        maxUnc = o.optDouble("maxUnc")
        reroutes = o.optInt("reroutes")
        lastTickMs = -1L
        // Unknown how far the car moved while the app was dead: widen the uncertainty with the gap.
        val uncertainty = (o.optDouble("unc", 100.0) + gapS * RESTORE_DRIFT_M_PER_S).coerceAtMost(3000.0)
        val now = SystemClock.elapsedRealtime()
        val estimator = NavigationEstimator.entries.firstOrNull { it.name == o.optString("estimator") } ?: NavigationEstimator.KOTLIN
        engine.start(restoredRoute, restoredDestination, waypoints, now, startAccuracyM = uncertainty, mode = mode, estimator = estimator)
        engine.resumeAt(o.optDouble("s"))
        // The killed process left the gzip stream unterminated: salvage it before appending.
        runCatching { TripFormat.repair(File(dir, recordingName)) }.onFailure { log("trip_repair_failed ${it.message}") }
        openRecorder(append = true)
        record(TripEvent.Start(now, restoredDestination, waypoints, uncertainty))
        record(TripEvent.Mode(now, mode))
        record(TripEvent.Estimator(now, engine.estimator))
        record(TripEvent.RouteSet(now, restoredRoute))
        log("trip_restored $id gap=${gapS.toInt()}s s=${o.optDouble("s").toInt()} unc=${uncertainty.toInt()}")
        persist(force = true)
        return true
    }

    /** Encode [route] for [persist] on the I/O thread (queued before any persist that needs it). */
    private fun encodeRoute(route: Route) = io.execute { routeEncoded = RouteCodec.encode(route) }

    /** Save the active trip to `active.json` (at most every 10 s unless [force]) so it can be restored after a kill. */
    private fun persist(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersistMs < PERSIST_EVERY_MS) return
        lastPersistMs = now
        val tripId = id ?: return
        val r = route ?: return
        val d = destination ?: return
        val st = engine.state
        // Small fields are copied now (on the main thread, where they change); the route string is
        // added on the I/O thread, because building JSON with it takes long enough to be noticed.
        val o = JSONObject()
            .put("id", tripId).put("recording", recordingName).put("start", startWall).put("savedAt", System.currentTimeMillis())
            .put("destLat", d.lat).put("destLon", d.lon).put("via", JSONArray(waypoints.flatMap { listOf(it.lat, it.lon) }))
            .put("startAcc", startAccuracy).put("s", engine.progressS).put("unc", st.uncertaintyM).put("mode", mode.name)
            .put("estimator", engine.estimator.name)
            .put("driven", drivenM).put("moving", movingS).put("blindS", blindS).put("blindM", blindM)
            .put("maxUnc", maxUnc).put("reroutes", reroutes)
        io.execute {
            if (id == null) return@execute // the trip ended meanwhile
            val text = o.put("route", routeEncoded.ifEmpty { RouteCodec.encode(r) }).toString()
            val tmp = File(dir, "active.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(activeFile)
        }
    }

    // ------------------------------------------------------------------ recording + history

    /** Open the recording file and route the positioning hub's raw inputs into it. */
    private fun openRecorder(append: Boolean) {
        recorder?.close()
        recorder = runCatching { TripRecorder.open(File(dir, recordingName), append) }.onFailure { log("trip_record_failed ${it.message}") }.getOrNull()
        hub.recorder = { e -> record(e) }
    }

    /** Write one event, on the background I/O thread. */
    private fun record(e: TripEvent) {
        val r = recorder ?: return
        io.execute { r.record(e) }
    }

    /** The recording (.rec.gz) of trip [t]. */
    fun recordingFile(t: TripSummary): File = File(dir, t.recording)

    /** All saved trips, newest first. */
    private fun loadHistory(): List<TripSummary> =
        runCatching { index.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { TripSummary.fromJson(JSONObject(it)) }.getOrNull() } }
            .getOrDefault(emptyList())
            .sortedByDescending { it.startWallMs }

    /** Replace the history index with [change] applied to it (after a delete or edit), on the I/O thread. */
    private fun rewrite(change: (List<TripSummary>) -> List<TripSummary>) = io.execute {
        val list = change(loadHistory())
        val tmp = File(dir, "index.jsonl.tmp")
        tmp.writeText(list.sortedBy { it.startWallMs }.joinToString("") { it.toJson().toString() + "\n" })
        tmp.renameTo(index)
        _history.value = loadHistory()
    }

    /** Delete a trip and its recording. */
    fun delete(t: TripSummary) {
        io.execute { File(dir, t.recording).delete() }
        rewrite { list -> list.filter { it.id != t.id } }
    }

    /** Store the road length found by map matching ("Snap to roads"). */
    fun setMatchedLength(t: TripSummary, lengthM: Double) {
        rewrite { list -> list.map { if (it.id == t.id) it.copy(matchedLengthM = lengthM) else it } }
    }

    fun totals(): HistoryTotals = _history.value.let { h ->
        HistoryTotals(h.size, h.sumOf { it.drivenM }, h.sumOf { it.durationS }, h.sumOf { it.blindM })
    }

    companion object {
        private const val PERSIST_EVERY_MS = 10_000L

        /** Restore only trips interrupted less than 3 hours ago. */
        private const val MAX_RESTORE_GAP_S = 3 * 3600.0

        /** Assumed drift while the app was dead (~city driving), m/s. */
        private const val RESTORE_DRIFT_M_PER_S = 8.0
    }
}

/** Tracks extracted from a recording for display. */
data class TripTracks(val gps: List<GeoPoint>, val engine: List<GeoPoint>, val goodGpsCount: Int)

/** Re-run the trust classifier over a recording so only trusted GPS fixes form the "real" track. */
fun extractTracks(file: File, area: ServiceArea): TripTracks {
    val events = TripFormat.read(file)
    var now = 0L
    var offset = 0L
    val hub = PositioningHub(area = area, wallClock = { now + offset })
    val gps = ArrayList<GeoPoint>()
    val engine = ArrayList<GeoPoint>()
    for (e in events.sortedBy { it.elapsedMs }) {
        now = e.elapsedMs
        when (e) {
            is TripEvent.Fix -> {
                if (offset == 0L && e.fix.source == FixSource.GPS) offset = e.fix.timeMs - e.fix.elapsedMs
                val v = hub.onFix(e.fix)
                if (v?.level == TrustLevel.GOOD) {
                    val p = e.fix.point
                    if (gps.isEmpty() || Geo.distance(gps.last(), p) >= 5) gps += p
                }
            }

            is TripEvent.Gnss -> hub.onGnssStatus(e.visible, e.used, e.meanCn0Used, e.cn0SpreadUsed, e.meanCn0Visible, e.dualFrequencyUsed, e.elapsedMs)

            is TripEvent.Agc -> hub.onAgc(e.agcDb, e.elapsedMs)

            is TripEvent.Estimate -> {
                val p = GeoPoint(e.lat, e.lon)
                if (engine.isEmpty() || Geo.distance(engine.last(), p) >= 5) engine += p
            }

            else -> Unit
        }
    }
    return TripTracks(gps, engine, gps.size)
}
