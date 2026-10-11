package org.imunav.app.trips

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.ImuSample
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.record.RecordingFailure
import org.imunav.core.record.RecordingOverload
import org.imunav.core.record.RecordingSession
import org.imunav.core.record.RouteCodec
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripTimeline
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import org.imunav.core.util.LatestWork
import org.imunav.core.util.OrderedBuffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/** Recording health is independent of navigation and remains visible after a storage failure. */
enum class RecordingStatus {
    IDLE,
    RECORDING,
    COMPLETE,
    OVERLOAD,
    WRITE_FAILED,
    ;

    val partial: Boolean get() = this == OVERLOAD || this == WRITE_FAILED
}

/** Translate terminal core failures without exposing exception messages or file paths. */
private fun RecordingFailure.status(): RecordingStatus = when (this) {
    RecordingFailure.OVERLOAD -> RecordingStatus.OVERLOAD
    RecordingFailure.WRITE_FAILED -> RecordingStatus.WRITE_FAILED
}

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
    val recordingStatus: RecordingStatus = RecordingStatus.COMPLETE,
) {
    val avgMovingKmh: Double get() = if (movingS > 0) drivenM / movingS * 3.6 else 0.0

    /** Serialize for the history index (one JSON object per line). */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("start", startWallMs).put("end", endWallMs)
        .put("destLat", destination?.lat).put("destLon", destination?.lon)
        .put("arrived", arrived).put("driven", drivenM).put("duration", durationS).put("moving", movingS)
        .put("blindS", blindS).put("blindM", blindM).put("maxUnc", maxUncertaintyM).put("routeLen", routeLengthM)
        .put("reroutes", reroutes).put("recording", recording).put("matched", matchedLengthM).put("mode", mode.name)
        .put("recordingStatus", recordingStatus.name)

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
            recordingStatus = RecordingStatus.entries.firstOrNull { it.name == o.optString("recordingStatus") } ?: RecordingStatus.COMPLETE,
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
 * An injected recording buffer must use the same serial I/O worker as the manager.
 */
class TripManager(
    private val context: Context,
    private val hub: PositioningHub,
    private val engine: NavigationEngine,
    private val log: (String) -> Unit,
    private val io: ExecutorService = Executors.newSingleThreadExecutor(),
    private val recordingBuffer: OrderedBuffer = RecordingSession.newBuffer(io),
) {
    /** Optional diagnostics sink; recording remains independent of upload availability. */
    var onArchiveStart: ((String) -> Unit)? = null
    var onArchiveCompleted: ((String) -> Unit)? = null
    var onDiagnosticStart: ((String) -> Unit)? = null
    var onDiagnosticEvent: ((String, TripEvent) -> Unit)? = null

    /** Session-only export sink, independent of developer diagnostics upload. */
    var onProfileEvent: ((TripEvent) -> Unit)? = null
    var onDiagnosticEnd: ((String) -> Unit)? = null
    private val dir = File(context.filesDir, "trips")
    private val index = File(dir, "index.jsonl")
    private val activeFile = File(dir, "active.json")

    private var recorder: RecordingSession? = null
    private var session: RecordingSession? = null
    private var recordingGeneration = 0L
    private val main = Handler(Looper.getMainLooper())
    private val _recordingStatus = MutableStateFlow(RecordingStatus.IDLE)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()
    val recordingStats get() = recordingBuffer.stats

    /** Recording and state files share this dispatcher; the injected serial worker preserves order. */
    private val ioDispatcher = io.asCoroutineDispatcher()
    private val snapshots = LatestWork(io)

    /**
     * [route] encoded once per route change (it can be large), reused by every [persist].
     * Only touched on the [io] thread: encoding a long route takes long enough to freeze the UI.
     */
    private var routeEncoded = ""
    private var encodedRoute: Route? = null

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
        io.execute {
            dir.mkdirs()
            _history.value = loadHistory()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Navigation started: open a new recording and reset the statistics. */
    fun begin(route: Route, destination: GeoPoint, waypoints: List<GeoPoint>, startAccuracyM: Double, mode: TravelMode = TravelMode.CAR) {
        end(arrived = false) // close anything left open
        recordingGeneration++
        _recordingStatus.value = RecordingStatus.RECORDING
        this.mode = mode
        val stamp = RecordingSession.newId()
        id = stamp
        onArchiveStart?.invoke(stamp)
        onDiagnosticStart?.invoke(stamp)
        recordingName = "trip-$stamp.rec.gz"
        startWall = System.currentTimeMillis()
        this.destination = destination
        this.waypoints = waypoints
        this.startAccuracy = startAccuracyM
        this.route = route
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

    /** Finish the trip; an explicit stop keeps even a short ride in history. */
    fun end(arrived: Boolean, keepShortTrip: Boolean = false) {
        val tripId = id ?: return
        record(TripEvent.Stop(SystemClock.elapsedRealtime()))
        val finished = session
        session = null
        recorder = null
        hub.recorder = null
        io.execute {
            if (activeFile.isFile && JSONObject(activeFile.readText()).optString("id") == tripId) activeFile.delete()
        }
        val end = System.currentTimeMillis()
        val summary = TripSummary(
            tripId, startWall, end, destination, arrived, drivenM, (end - startWall) / 1000.0, movingS, blindS, blindM,
            maxUnc, route?.length ?: 0.0, reroutes, recordingName, mode = mode,
            recordingStatus = if (_recordingStatus.value.partial) _recordingStatus.value else RecordingStatus.COMPLETE,
        )
        id = null
        _recordingStatus.value = RecordingStatus.IDLE
        // Skip accidental starts (no movement at all) to keep the history meaningful.
        if (!keepShortTrip && !summary.recordingStatus.partial && finished?.failure == null && summary.drivenM < 50 && summary.durationS < 120) {
            finished?.finish(discard = true)
            log("trip_discarded $tripId")
            io.execute {
                onDiagnosticEnd?.invoke(tripId)
                onArchiveCompleted?.invoke(tripId)
            }
            return
        }
        finished?.finish()
        log("trip_end $tripId driven=${drivenM.toInt()}m blind=${blindS.toInt()}s")
        io.execute {
            onDiagnosticEnd?.invoke(tripId)
            index.appendText(summary.copy(recordingStatus = finished?.failure?.status() ?: summary.recordingStatus).toJson().toString() + "\n")
            _history.value = loadHistory()
            onArchiveCompleted?.invoke(tripId)
        }
    }

    // ------------------------------------------------------------------ surviving process death

    /** Read and decode on the ordered recording worker, without touching live state. */
    suspend fun prepareRestore(): RestoredTrip? = withContext(ioDispatcher) {
        RestoredTrip.read(activeFile, System.currentTimeMillis())
    }

    /** Install a validated snapshot only after the foreground service is running. Main-thread only. */
    fun restore(saved: RestoredTrip): Boolean {
        if (active || engine.state.active) return false
        val uncertainty = saved.uncertaintyAt(System.currentTimeMillis()) ?: return false
        val summary = saved.summary
        val restoredDestination = requireNotNull(summary.destination)
        val now = SystemClock.elapsedRealtime()
        // Do not publish a trip ID if engine installation fails before recording can begin.
        engine.start(saved.route, restoredDestination, saved.waypoints, now, startAccuracyM = uncertainty, mode = summary.mode, estimator = saved.estimator)
        engine.resumeAt(saved.progressM)
        recordingGeneration++
        _recordingStatus.value = if (summary.recordingStatus.partial) summary.recordingStatus else RecordingStatus.RECORDING
        id = summary.id
        recordingName = summary.recording
        startWall = summary.startWallMs
        destination = restoredDestination
        waypoints = saved.waypoints
        startAccuracy = saved.startAccuracyM
        mode = summary.mode
        route = saved.route
        io.execute {
            routeEncoded = saved.encodedRoute
            encodedRoute = saved.route
        }
        drivenM = summary.drivenM
        movingS = summary.movingS
        blindS = summary.blindS
        blindM = summary.blindM
        maxUnc = summary.maxUncertaintyM
        reroutes = summary.reroutes
        lastTickMs = -1L
        onArchiveStart?.invoke(summary.id)
        onDiagnosticStart?.invoke(summary.id)
        if (!_recordingStatus.value.partial) openRecorder(append = true)
        record(TripEvent.Start(now, restoredDestination, waypoints, uncertainty))
        record(TripEvent.Mode(now, mode))
        record(TripEvent.Estimator(now, engine.estimator))
        record(TripEvent.RouteSet(now, saved.route))
        record(TripEvent.Resume(now, engine.progressS))
        val gapS = (System.currentTimeMillis() - saved.savedAt) / 1000.0
        log("trip_restored $id gap=${gapS.toInt()}s s=${saved.progressM.toInt()} unc=${uncertainty.toInt()}")
        persist(force = true)
        return true
    }

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
            .put("recordingStatus", _recordingStatus.value.name)
            .put("destLat", d.lat).put("destLon", d.lon).put("via", JSONArray(waypoints.flatMap { listOf(it.lat, it.lon) }))
            .put("startAcc", startAccuracy).put("s", engine.progressS).put("unc", st.uncertaintyM).put("mode", mode.name)
            .put("estimator", engine.estimator.name)
            .put("driven", drivenM).put("moving", movingS).put("blindS", blindS).put("blindM", blindM)
            .put("maxUnc", maxUnc).put("reroutes", reroutes)
        snapshots.submit {
            if (id == tripId) {
                if (encodedRoute !== r) {
                    routeEncoded = RouteCodec.encode(r)
                    encodedRoute = r
                }
                val text = o.put("route", routeEncoded).toString()
                val tmp = File(dir, "active.json.tmp")
                tmp.writeText(text)
                check(tmp.renameTo(activeFile)) { "Cannot save trip snapshot" }
            }
        }
    }

    // ------------------------------------------------------------------ recording + history

    /** Open the recording file and route the positioning hub's raw inputs into it. */
    private fun openRecorder(append: Boolean) {
        recorder?.finish()
        val tripId = id
        val generation = recordingGeneration
        val next = RecordingSession(
            File(dir, recordingName),
            io,
            append,
            onRecorded = { event ->
                tripId?.let { onDiagnosticEvent?.invoke(it, event) }
                onProfileEvent?.invoke(event)
            },
            arrivalClock = SystemClock::elapsedRealtime,
            buffer = recordingBuffer,
            onFailure = { cause ->
                val status = if (cause is RecordingOverload) RecordingStatus.OVERLOAD else RecordingStatus.WRITE_FAILED
                main.post {
                    if (id == tripId && recordingGeneration == generation) {
                        recorder = null
                        hub.recorder = null
                        _recordingStatus.value = status
                        log("trip_record_failed reason=${status.name.lowercase()}")
                        persist(force = true)
                    }
                }
            },
        )
        session = next
        recorder = next
        hub.recorder = { event -> record(event) }
    }

    /** Write one event, on the background I/O thread. */
    private fun record(e: TripEvent) {
        val current = recorder ?: return
        current.record(e)
        current.failure?.let { failure ->
            recorder = null
            hub.recorder = null
            _recordingStatus.value = failure.status()
        }
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
    for (entry in TripTimeline.schedule(events)) {
        val e = entry.event
        now = entry.elapsedMs
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
