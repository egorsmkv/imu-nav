package org.imunav.core.record

import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.eskf.InertialKind
import org.imunav.core.imu.eskf.InertialSample
import org.imunav.core.imu.eskf.Vector3
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Reject non-finite numeric payloads before they can enter replay state. */
private fun String.recordingDouble(): Double = toDouble().also { require(it.isFinite()) }

/** Float parsing can overflow even when the corresponding Double would be finite. */
private fun String.recordingFloat(): Float = toFloat().also { require(it.isFinite()) }

/** Everything the positioning pipeline consumed during a trip, in time order. */
sealed class TripEvent {
    abstract val elapsedMs: Long

    /** Recorder arrival clock, separate from sensor measurement time; absent in legacy files. */
    var arrivalElapsedMs: Long? = null
        internal set

    data class Fix(val fix: RawFix) : TripEvent() {
        override val elapsedMs get() = fix.elapsedMs
    }

    class Imu(val sample: ImuSample) : TripEvent() {
        override val elapsedMs get() = sample.elapsedMs
    }

    /** Experimental raw sensor input. Arrival time orders replay; the sample retains measurement ns. */
    data class Inertial(override val elapsedMs: Long, val sample: InertialSample) : TripEvent()

    data class Gnss(
        override val elapsedMs: Long,
        val visible: Int,
        val used: Int,
        val meanCn0Used: Float?,
        val cn0SpreadUsed: Float?,
        val meanCn0Visible: Float?,
        val dualFrequencyUsed: Int,
    ) : TripEvent()

    data class Agc(override val elapsedMs: Long, val agcDb: Float?) : TripEvent()

    /** Navigation started (or restored) towards [destination]. */
    data class Start(override val elapsedMs: Long, val destination: GeoPoint, val waypoints: List<GeoPoint>, val startAccuracyM: Double) : TripEvent()

    /** A route became active (at start and after each reroute). */
    class RouteSet(override val elapsedMs: Long, val route: Route) : TripEvent()

    /** Restored progress after RouteSet; the preceding Start contains the restored uncertainty. */
    data class Resume(override val elapsedMs: Long, val s: Double) : TripEvent() {
        init {
            require(s.isFinite() && s >= 0.0)
        }
    }

    data class Stop(override val elapsedMs: Long) : TripEvent()

    /** The engine's own estimate at a tick (for trip history; ignored by replay). */
    data class Estimate(override val elapsedMs: Long, val lat: Double, val lon: Double, val s: Double, val uncertaintyM: Double, val source: String) : TripEvent()

    /** One step from the phone's step detector (walking trips). */
    data class StepTaken(override val elapsedMs: Long) : TripEvent()

    /** The travel mode of the trip; written right after [Start] (missing in old recordings = car). */
    data class Mode(override val elapsedMs: Long, val mode: TravelMode) : TripEvent()

    /** Actual position owner. Missing in old trips = Kotlin. */
    data class Estimator(override val elapsedMs: Long, val estimator: NavigationEstimator) : TripEvent()

    /** The car's own speed from an OBD-II adapter, km/h. */
    data class VehicleSpeed(override val elapsedMs: Long, val kmh: Float) : TripEvent()

    /** Air pressure from the phone's barometer, hPa. */
    data class Pressure(override val elapsedMs: Long, val hPa: Float) : TripEvent()
}

/**
 * Line-oriented trip recording (`*.rec.gz`). One event per line, comma-separated, first field is
 * the type: F fix, I imu, S satellites, A agc, D start, M travel mode, R route, Q restored progress, P step, X stop,
 * E engine estimate, V vehicle (OBD-II) speed, B barometer, K estimator selection, U raw inertial sensor. Empty fields = null. Readers skip
 * types they do not know, so new event types keep old app versions able to read recordings.
 */
object TripFormat {
    const val VERSION = 1
    internal const val ARRIVAL_PREFIX = "# arrival_ms="

    private fun n(v: Any?): String = when (v) {
        null -> ""
        is Float -> if (v.isNaN()) "" else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        is Double -> if (v.isNaN()) "" else String.format(Locale.US, "%.7f", v).trimEnd('0').trimEnd('.')
        else -> v.toString()
    }

    /** One event → one line (see the format description above). */
    fun encode(e: TripEvent): String = when (e) {
        // Do not round raw IMU values with n(): bias estimation needs their original precision.
        is TripEvent.Inertial -> e.sample.let {
            listOf("U", e.elapsedMs, it.timestampNs, it.kind.name, it.vector.x, it.vector.y, it.vector.z, it.scalar).joinToString(",")
        }

        is TripEvent.Fix -> e.fix.let { f ->
            listOf(
                "F", f.elapsedMs, f.source.name, f.timeMs, n(f.lat), n(f.lon), n(f.altitudeM), n(f.speedMps), n(f.bearingDeg), n(f.accuracyM),
                n(f.verticalAccuracyM), n(f.speedAccuracyMps), if (f.isMock) 1 else 0,
            ).joinToString(",")
        }

        is TripEvent.Imu -> e.sample.let { s ->
            val a = s.linearAcc
            val g = s.gyro
            listOf(
                "I", s.elapsedMs, n(s.headingDeg), n(s.yawRateDegS), n(a?.getOrNull(0)), n(a?.getOrNull(1)), n(a?.getOrNull(2)),
                n(g?.getOrNull(0)), n(g?.getOrNull(1)), n(g?.getOrNull(2)),
            ).joinToString(",")
        }

        is TripEvent.Gnss -> listOf("S", e.elapsedMs, e.visible, e.used, n(e.meanCn0Used), n(e.cn0SpreadUsed), n(e.meanCn0Visible), e.dualFrequencyUsed).joinToString(",")

        is TripEvent.Agc -> listOf("A", e.elapsedMs, n(e.agcDb)).joinToString(",")

        is TripEvent.Start -> (
            listOf("D", e.elapsedMs, n(e.destination.lat), n(e.destination.lon), n(e.startAccuracyM)) +
                e.waypoints.flatMap { listOf(n(it.lat), n(it.lon)) }
            ).joinToString(",")

        is TripEvent.RouteSet -> "R,${e.elapsedMs},${RouteCodec.encode(e.route)}"

        is TripEvent.Resume -> "Q,${e.elapsedMs},${n(e.s)}"

        is TripEvent.Stop -> "X,${e.elapsedMs}"

        is TripEvent.StepTaken -> "P,${e.elapsedMs}"

        is TripEvent.Mode -> "M,${e.elapsedMs},${e.mode.name}"

        is TripEvent.Estimator -> "K,${e.elapsedMs},${e.estimator.name}"

        is TripEvent.VehicleSpeed -> "V,${e.elapsedMs},${n(e.kmh)}"

        is TripEvent.Pressure -> "B,${e.elapsedMs},${n(e.hPa)}"

        is TripEvent.Estimate -> listOf("E", e.elapsedMs, n(e.lat), n(e.lon), String.format(Locale.US, "%.1f", e.s), e.uncertaintyM.toInt(), e.source).joinToString(",")
    }

    /** One line → one event; null for comments, blank lines and anything malformed (e.g. a line cut off by a crash). */
    fun decode(line: String): TripEvent? {
        if (line.isBlank() || line.startsWith("#")) return null
        val fields = if (line.startsWith("R,")) line.split(',', limit = 3) else line.split(',')

        // Field [i] as a number, or null when the field is empty ("unknown").
        fun double(i: Int) = fields.getOrNull(i)?.takeIf { it.isNotEmpty() }?.recordingDouble()
        fun float(i: Int) = fields.getOrNull(i)?.takeIf { it.isNotEmpty() }?.recordingFloat()
        val time = fields.getOrNull(1)?.toLongOrNull() ?: return null
        return runCatching {
            when (fields[0]) {
                "U" -> TripEvent.Inertial(
                    time,
                    InertialSample(
                        fields[2].toLong(),
                        InertialKind.valueOf(fields[3]),
                        Vector3(fields[4].recordingDouble(), fields[5].recordingDouble(), fields[6].recordingDouble()),
                        fields[7].recordingDouble(),
                    ),
                )

                "F" -> TripEvent.Fix(
                    RawFix(
                        FixSource.valueOf(
                            fields[2],
                        ),
                        fields[3].toLong(), time, fields[4].recordingDouble(), fields[5].recordingDouble(), double(6), float(7), float(8), float(9), float(10), float(11),
                        fields.getOrNull(12) == "1",
                    ),
                )

                "I" -> {
                    // Three numbers x,y,z starting at field [first], or null when absent.
                    fun vector(first: Int): FloatArray? {
                        val x = float(first)
                        val y = float(first + 1)
                        val z = float(first + 2)
                        if (x == null && y == null && z == null) return null
                        return floatArrayOf(requireNotNull(x), requireNotNull(y), requireNotNull(z))
                    }
                    val acc = vector(4)
                    val gyro = vector(7)
                    TripEvent.Imu(ImuSample(time, float(2), float(3), acc, gyro))
                }

                "S" -> TripEvent.Gnss(time, fields[2].toInt(), fields[3].toInt(), float(4), float(5), float(6), fields[7].toInt())

                "A" -> TripEvent.Agc(time, float(2))

                "D" -> {
                    require(fields.size >= 5 && (fields.size - 5) % 2 == 0)
                    TripEvent.Start(
                        time,
                        GeoPoint(fields[2].recordingDouble(), fields[3].recordingDouble()),
                        (5 until fields.size step 2).map { GeoPoint(fields[it].recordingDouble(), fields[it + 1].recordingDouble()) },
                        double(4) ?: 0.0,
                    )
                }

                "R" -> TripEvent.RouteSet(time, RouteCodec.decode(fields[2]))

                "Q" -> TripEvent.Resume(time, fields[2].recordingDouble())

                "X" -> TripEvent.Stop(time)

                "P" -> TripEvent.StepTaken(time)

                "M" -> TripEvent.Mode(time, TravelMode.valueOf(fields[2]))

                "K" -> TripEvent.Estimator(time, NavigationEstimator.valueOf(fields[2]))

                "V" -> TripEvent.VehicleSpeed(time, fields[2].recordingFloat())

                "B" -> TripEvent.Pressure(time, fields[2].recordingFloat())

                "E" -> TripEvent.Estimate(
                    time, fields[2].recordingDouble(), fields[3].recordingDouble(), fields[4].recordingDouble(),
                    fields[5].recordingDouble(), fields.getOrNull(6).orEmpty(),
                )

                else -> null
            }
        }.getOrNull()
    }

    /**
     * Read all events. Tolerates a truncated tail (the app was killed before the gzip stream was
     * closed): everything flushed before the cut is returned.
     */
    fun read(input: InputStream): List<TripEvent> {
        val buffered = input.buffered()
        buffered.mark(2)
        val magic = (buffered.read() shl 8) or buffered.read()
        buffered.reset()
        // Decompress into memory first: readers buffer ahead and would drop the tail on an EOF error.
        val bytes = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        var incomplete = false
        try {
            // Header construction can itself fail when a process died before writing the header.
            val stream = if (magic == 0x1f8b) GZIPInputStream(buffered) else buffered
            stream.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    bytes.write(buf, 0, n)
                }
            }
        } catch (_: IOException) {
            incomplete = true
        } finally {
            runCatching { buffered.close() }
        }
        val out = ArrayList<TripEvent>()
        // String(bytes, charset), not ByteArrayOutputStream.toString(Charset): the latter needs Android 13.
        val text = String(bytes.toByteArray(), Charsets.UTF_8)
        // A cut number (e.g. X,123 -> X,12) can still parse: only newline-terminated events
        // are trustworthy after an I/O failure. Clean text input may omit its final newline.
        val complete = if (incomplete) text.substring(0, text.lastIndexOf('\n') + 1) else text
        var arrivalMs: Long? = null
        BufferedReader(complete.reader()).lineSequence().forEach { line ->
            if (line.startsWith(ARRIVAL_PREFIX)) {
                arrivalMs = line.removePrefix(ARRIVAL_PREFIX).toLongOrNull()?.takeIf { it >= 0 }
            } else if (!line.startsWith("#") && line.isNotBlank()) {
                decode(line)?.let { event ->
                    event.arrivalElapsedMs = arrivalMs
                    out += event
                }
                // A malformed/unknown event consumes its metadata, too.
                arrivalMs = null
            }
        }
        return out
    }

    /** Repair via a completed temporary gzip and atomic replacement; failures leave the original in place. */
    fun repair(file: File) = TripRepair.repair(file)

    fun read(file: File): List<TripEvent> = file.inputStream().use { read(it) }
}

/**
 * Appends events to a gzip file; flushes (sync-flush) every couple of seconds so a crash or kill
 * loses at most the last moments. Thread-safe.
 */
class TripRecorder(out: OutputStream, private val flushEveryMs: Long = 2000) : Closeable {
    private val gzip = GZIPOutputStream(out, 1 shl 16, true)
    private val writer: Writer = gzip.bufferedWriter()
    private var lastFlush = System.currentTimeMillis()
    private var closed = false

    init {
        writer.write("# blind-driver trip v${TripFormat.VERSION}\n")
    }

    fun record(e: TripEvent) = record(e, e.arrivalElapsedMs)

    /** Writes arrival metadata and its event together, so concurrent producers cannot interleave them. */
    @Synchronized
    fun record(e: TripEvent, arrivalMs: Long?) {
        if (closed) return
        require(arrivalMs == null || arrivalMs >= 0)
        arrivalMs?.let { writer.write("${TripFormat.ARRIVAL_PREFIX}$it\n") }
        writer.write(TripFormat.encode(e))
        writer.write("\n")
        val now = System.currentTimeMillis()
        if (now - lastFlush >= flushEveryMs) {
            writer.flush()
            lastFlush = now
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        writer.close()
    }

    companion object {
        fun open(file: File, append: Boolean = false): TripRecorder = TripRecorder(FileOutputStream(file, append))
    }
}

/** Compact single-line route serialization (geometry, steps, limits) for recordings and saved state. */
object RouteCodec {
    private fun esc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun unesc(s: String) = URLDecoder.decode(s, "UTF-8")

    fun encode(r: Route): String {
        val geom = r.geometry.joinToString(";") { String.format(Locale.US, "%.6f:%.6f", it.lat, it.lon) }
        val steps = r.steps.joinToString(";") { s ->
            listOf(
                esc(s.type),
                esc(s.modifier.orEmpty()),
                esc(s.name),
                String.format(Locale.US, "%.1f", s.distanceM),
                String.format(Locale.US, "%.1f", s.durationS),
                s.geometryIndex,
                s.roundaboutExit ?: "",
            ).joinToString("~")
        }
        val limits = r.maxspeedKmh.joinToString(":") { it?.toString().orEmpty() }
        val signals = r.signals.joinToString(";") { String.format(Locale.US, "%.6f:%.6f", it.lat, it.lon) }
        // Heights in whole decimetres keep long routes compact; empty = no elevation data.
        val heights = r.elevationM?.joinToString(":") { Math.round(it * 10).toString() }.orEmpty()
        return listOf(String.format(Locale.US, "%.1f", r.durationS), geom, steps, limits, signals, esc(r.summary), heights).joinToString("|")
    }

    fun decode(s: String): Route {
        val p = s.split('|')
        val geometry = p[1].split(';').filter { it.isNotEmpty() }.map {
            val (a, b) = it.split(':')
            GeoPoint(a.recordingDouble(), b.recordingDouble())
        }
        val steps = p[2].split(';').filter { it.isNotEmpty() }.map { st ->
            val f = st.split('~')
            Step(unesc(f[0]), unesc(f[1]).ifEmpty { null }, unesc(f[2]), f[3].recordingDouble(), f[4].recordingDouble(), f[5].toInt(), f.getOrNull(6)?.toIntOrNull())
        }
        val limits = p.getOrNull(3).orEmpty().let { if (it.isEmpty()) emptyList() else it.split(':').map { v -> v.toIntOrNull() } }
        val signals = p.getOrNull(4).orEmpty().split(';').filter { it.isNotEmpty() }.map {
            val (a, b) = it.split(':')
            GeoPoint(a.recordingDouble(), b.recordingDouble())
        }
        val heights = p.getOrNull(6).orEmpty().takeIf { it.isNotEmpty() }?.split(':')?.map { it.recordingDouble() / 10.0 }?.toDoubleArray()
            ?.takeIf { it.size == geometry.size }
        return Route(geometry, steps, p[0].recordingDouble(), limits, signals, unesc(p.getOrNull(5).orEmpty()), elevationM = heights)
    }
}
