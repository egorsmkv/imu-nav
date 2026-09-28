package org.blinddriver.core.record

import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.gnss.FixSource
import org.blinddriver.core.gnss.RawFix
import org.blinddriver.core.imu.ImuSample
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.Step
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

/** Everything the positioning pipeline consumed during a trip, in time order. */
sealed class TripEvent {
    abstract val elapsedMs: Long

    data class Fix(val fix: RawFix) : TripEvent() {
        override val elapsedMs get() = fix.elapsedMs
    }

    class Imu(val sample: ImuSample) : TripEvent() {
        override val elapsedMs get() = sample.elapsedMs
    }

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

    data class Stop(override val elapsedMs: Long) : TripEvent()

    /** The engine's own estimate at a tick (for trip history; ignored by replay). */
    data class Estimate(override val elapsedMs: Long, val lat: Double, val lon: Double, val s: Double, val uncertaintyM: Double, val source: String) : TripEvent()
}

/**
 * Line-oriented trip recording (`*.rec.gz`). One event per line, comma-separated, first field is
 * the type: F fix, I imu, S satellites, A agc, D start, R route, X stop, E engine estimate. Empty fields = null.
 */
object TripFormat {
    const val VERSION = 1

    private fun n(v: Any?): String = when (v) {
        null -> ""
        is Float -> if (v.isNaN()) "" else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        is Double -> if (v.isNaN()) "" else String.format(Locale.US, "%.7f", v).trimEnd('0').trimEnd('.')
        else -> v.toString()
    }

    /** One event → one line (see the format description above). */
    fun encode(e: TripEvent): String = when (e) {
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

        is TripEvent.Stop -> "X,${e.elapsedMs}"

        is TripEvent.Estimate -> listOf("E", e.elapsedMs, n(e.lat), n(e.lon), String.format(Locale.US, "%.1f", e.s), e.uncertaintyM.toInt(), e.source).joinToString(",")
    }

    /** One line → one event; null for comments, blank lines and anything malformed (e.g. a line cut off by a crash). */
    fun decode(line: String): TripEvent? {
        if (line.isBlank() || line.startsWith("#")) return null
        val fields = if (line.startsWith("R,")) line.split(',', limit = 3) else line.split(',')

        // Field [i] as a number, or null when the field is empty ("unknown").
        fun double(i: Int) = fields.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()
        fun float(i: Int) = fields.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toFloat()
        val time = fields.getOrNull(1)?.toLongOrNull() ?: return null
        return runCatching {
            when (fields[0]) {
                "F" -> TripEvent.Fix(
                    RawFix(
                        FixSource.valueOf(
                            fields[2],
                        ),
                        fields[3].toLong(), time, fields[4].toDouble(), fields[5].toDouble(), double(6), float(7), float(8), float(9), float(10), float(11),
                        fields.getOrNull(12) == "1",
                    ),
                )

                "I" -> {
                    // Three numbers x,y,z starting at field [first], or null when absent.
                    fun vector(first: Int): FloatArray? = if (fields.getOrNull(first).isNullOrEmpty()) {
                        null
                    } else {
                        floatArrayOf(requireNotNull(float(first)), requireNotNull(float(first + 1)), requireNotNull(float(first + 2)))
                    }
                    val acc = vector(4)
                    val gyro = vector(7)
                    TripEvent.Imu(ImuSample(time, float(2), float(3), acc, gyro))
                }

                "S" -> TripEvent.Gnss(time, fields[2].toInt(), fields[3].toInt(), float(4), float(5), float(6), fields[7].toInt())

                "A" -> TripEvent.Agc(time, float(2))

                "D" -> TripEvent.Start(
                    time,
                    GeoPoint(fields[2].toDouble(), fields[3].toDouble()),
                    (5 until fields.size - 1 step 2).map { GeoPoint(fields[it].toDouble(), fields[it + 1].toDouble()) },
                    double(4) ?: 0.0,
                )

                "R" -> TripEvent.RouteSet(time, RouteCodec.decode(fields[2]))

                "X" -> TripEvent.Stop(time)

                "E" -> TripEvent.Estimate(time, fields[2].toDouble(), fields[3].toDouble(), fields[4].toDouble(), fields[5].toDouble(), fields.getOrNull(6).orEmpty())

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
        val stream = if (magic == 0x1f8b) GZIPInputStream(buffered) else buffered
        // Decompress into memory first: readers buffer ahead and would drop the tail on an EOF error.
        val bytes = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                bytes.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // Truncated stream: keep what was decompressed (a partial last line fails to decode).
        } finally {
            runCatching { stream.close() }
        }
        val out = ArrayList<TripEvent>()
        BufferedReader(bytes.toString(Charsets.UTF_8).reader()).lineSequence().forEach { line -> decode(line)?.let { out += it } }
        return out
    }

    /** Rewrite a possibly truncated recording as a clean gzip file (before appending to it). */
    fun repair(file: File) {
        if (!file.exists()) return
        val events = read(file)
        val tmp = File(file.parentFile, file.name + ".repair")
        TripRecorder(tmp.outputStream()).use { rec -> events.forEach { rec.record(it) } }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

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

    @Synchronized
    fun record(e: TripEvent) {
        if (closed) return
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
        runCatching { writer.close() }
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
        return listOf(String.format(Locale.US, "%.1f", r.durationS), geom, steps, limits, signals, esc(r.summary)).joinToString("|")
    }

    fun decode(s: String): Route {
        val p = s.split('|')
        val geometry = p[1].split(';').filter { it.isNotEmpty() }.map {
            val (a, b) = it.split(':')
            GeoPoint(a.toDouble(), b.toDouble())
        }
        val steps = p[2].split(';').filter { it.isNotEmpty() }.map { st ->
            val f = st.split('~')
            Step(unesc(f[0]), unesc(f[1]).ifEmpty { null }, unesc(f[2]), f[3].toDouble(), f[4].toDouble(), f[5].toInt(), f.getOrNull(6)?.toIntOrNull())
        }
        val limits = p.getOrNull(3).orEmpty().let { if (it.isEmpty()) emptyList() else it.split(':').map { v -> v.toIntOrNull() } }
        val signals = p.getOrNull(4).orEmpty().split(';').filter { it.isNotEmpty() }.map {
            val (a, b) = it.split(':')
            GeoPoint(a.toDouble(), b.toDouble())
        }
        return Route(geometry, steps, p[0].toDouble(), limits, signals, unesc(p.getOrNull(5).orEmpty()))
    }
}
