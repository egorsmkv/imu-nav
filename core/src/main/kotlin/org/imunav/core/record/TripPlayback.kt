package org.imunav.core.record

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.PositionSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** A recorded engine position; segment changes are discontinuities, never interpolation targets. */
data class PlaybackPosition(val timeMs: Long, val segment: Int, val point: GeoPoint, val uncertaintyM: Double, val source: String)

/** Planned geometry becomes active at this point on the recording timeline. */
data class PlaybackRoute(val timeMs: Long, val segment: Int, val points: List<GeoPoint>)

/** Minimal personal archive data. It deliberately excludes GPS fixes and raw sensor measurements. */
data class TripPlayback(val positions: List<PlaybackPosition>, val routes: List<PlaybackRoute>, val incomplete: Boolean)

/** Streams complete lines, retaining usable events when a killed process left a truncated gzip tail. */
object TripPlaybackReader {
    private const val MAX_LINE_BYTES = 16 * 1024 * 1024
    private const val MAX_POINTS = 200_000
    private const val MAX_ROUTES = 1000
    private const val SAMPLE_MS = 1000L

    /** Bounded extraction in file order. [checkCancelled] lets an IO caller stop long recordings. */
    fun read(input: InputStream, checkCancelled: () -> Unit = {}): TripPlayback {
        val collector = Collector()
        val buffered = input.buffered()
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        var incomplete = false
        try {
            val stream = if (gzip) GZIPInputStream(buffered) else buffered
            stream.use {
                val line = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled()
                    val count = it.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val byte = buffer[index]
                        if (byte == '\n'.code.toByte()) {
                            collector.line(line.toByteArray().toString(Charsets.UTF_8))
                            line.reset()
                        } else {
                            require(line.size() < MAX_LINE_BYTES) { "Playback recording line too large" }
                            line.write(byte.toInt())
                        }
                    }
                }
                incomplete = line.size() != 0
            }
        } catch (_: IOException) {
            incomplete = true
        }
        collector.finish()
        require(collector.positions.isNotEmpty()) { "No recorded positions" }
        return TripPlayback(collector.positions, collector.routes, incomplete || !collector.stopped)
    }

    /** Keeps a monotonic relative clock while preserving recording boundaries and source changes. */
    private class Collector {
        val positions = ArrayList<PlaybackPosition>()
        val routes = ArrayList<PlaybackRoute>()
        var stopped = false
        private var previousElapsed: Long? = null
        private var origin = 0L
        private var offset = 0L
        private var lastTime = 0L
        private var segment = 0
        private var geometryPoints = 0
        private var pending: PlaybackPosition? = null

        fun finish() {
            pending?.let {
                require(positions.size < MAX_POINTS) { "Playback track too large" }
                positions += it
            }
            pending = null
        }

        fun line(line: String) {
            if (line.firstOrNull() !in listOf('D', 'R', 'E', 'X', 'Q')) return
            val event = TripFormat.decode(line) ?: return
            val previous = previousElapsed
            if (previous == null) {
                origin = event.elapsedMs
            } else if (event.elapsedMs < previous || event is TripEvent.Start) {
                finish()
                segment++
                offset = lastTime
                origin = event.elapsedMs
            }
            previousElapsed = event.elapsedMs
            val time = offset + (event.elapsedMs - origin).coerceAtLeast(0)
            lastTime = time
            stopped = event is TripEvent.Stop
            when (event) {
                is TripEvent.Estimate -> addPosition(event, time)

                is TripEvent.RouteSet -> {
                    geometryPoints += event.route.geometry.size
                    require(routes.size < MAX_ROUTES && geometryPoints <= MAX_POINTS) { "Playback routes too large" }
                    routes += PlaybackRoute(time, segment, event.route.geometry)
                }

                is TripEvent.Stop -> finish()

                else -> Unit
            }
        }

        private fun addPosition(event: TripEvent.Estimate, time: Long) {
            if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0 ||
                !event.uncertaintyM.isFinite() || event.uncertaintyM < 0
            ) {
                return
            }
            val source = PositionSource.entries.firstOrNull { it.label == event.source || it.name == event.source }?.name ?: "NONE"
            val position = PlaybackPosition(time, segment, GeoPoint(event.lat, event.lon), event.uncertaintyM, source)
            val previous = positions.lastOrNull()
            if (previous != null && previous.segment == segment && previous.source == source && time - previous.timeMs < SAMPLE_MS) {
                pending = position
                return
            }
            pending = null
            require(positions.size < MAX_POINTS) { "Playback track too large" }
            positions += position
        }
    }
}
