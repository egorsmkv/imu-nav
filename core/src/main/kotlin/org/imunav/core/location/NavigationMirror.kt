package org.imunav.core.location

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.GuidanceState
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import java.io.ByteArrayOutputStream

/** Captured on the navigation thread; workers serialize it without reading mutable engine state. */
data class NavigationMirrorInput(val guidance: GuidanceState, val gpsState: GpsState, val verdict: TrustLevel?, val jammed: Boolean)

/** Pre-encoded immutable output retained once per transport, rather than once per receiver. */
data class NavigationMirrorPublication(val stateLine: ByteArray, val routeRevision: Long?, val routeFrames: List<ByteArray>)

/** The relay uses the same guidance/route contract as the local TCP listener. */
data class EncodedNavigationMirror(val publication: NavigationMirrorPublication, val guidanceJson: String?, val routeJson: String?)

/** Worker-owned encoder caches full geometry until the engine installs a different route. */
class NavigationMirrorEncoder {
    private val factory = JsonFactory()
    private var lastRoute: Route? = null
    private var lastDestination: GeoPoint? = null
    private var revision = 0L
    private var sequence = 0L
    private var routeJson: String? = null
    private var routeFrames = emptyList<ByteArray>()

    /** Route loops and JSON allocation belong on the sharing worker, never the main thread. */
    fun encode(sample: NavigationPositionSample, input: NavigationMirrorInput?, enabled: Boolean, nowMs: Long): EncodedNavigationMirror {
        val state = input?.guidance?.takeIf { enabled }
        val route = state?.route
        val destination = state?.destination
        if (route !== lastRoute || destination != lastDestination) {
            lastRoute = route
            lastDestination = destination
            revision++
            prepareRoute(route, destination)
        }
        val guidance = state?.let { guidanceJson(it, input) }
        val line = json {
            writeStringField("type", "state")
            writeNumberField("version", 1)
            writeObjectFieldStart("state")
            writeNumberField("sequence", ++sequence)
            writeNumberField("elapsed_ms", sample.elapsedMs)
            writeNumberField("utc_ms", sample.utcMs)
            writeNumberField("age_ms", (nowMs - sample.elapsedMs).coerceAtLeast(0))
            writeStringField("nmea", NavigationNmea.encode(sample))
            writeFieldName("guidance")
            if (guidance == null) writeNull() else writeRawValue(guidance)
            writeEndObject()
        }
        require(line.size < MAX_FRAME_BYTES) { "Oversized navigation state" }
        return EncodedNavigationMirror(
            NavigationMirrorPublication(line + NEWLINE, if (routeJson != null) revision else null, routeFrames),
            guidance,
            routeJson,
        )
    }

    private fun prepareRoute(route: Route?, requestedDestination: GeoPoint?) {
        routeJson = null
        routeFrames = emptyList()
        if (route == null || route.geometry.size !in 2..MAX_ROUTE_POINTS || route.geometry.any { !validPoint(it) }) return
        val destination = requestedDestination?.takeIf(::validPoint) ?: route.geometry.last()
        val encoded = json {
            writeNumberField("revision", revision)
            writeStringField("summary", bounded(route.summary, MAX_NAME_CHARS))
            pointField("destination", destination)
            writeArrayFieldStart("geometry")
            route.geometry.forEach { point(it) }
            writeEndArray()
        }
        if (encoded.size > MAX_ROUTE_BYTES) return
        routeJson = encoded.toString(Charsets.UTF_8)
        routeFrames = buildList {
            add(
                json {
                    writeStringField("type", "route_start")
                    writeNumberField("revision", revision)
                    writeNumberField("points", route.geometry.size)
                    writeStringField("summary", bounded(route.summary, MAX_NAME_CHARS))
                    pointField("destination", destination)
                } + NEWLINE,
            )
            route.geometry.chunked(CHUNK_POINTS).forEachIndexed { index, points ->
                add(
                    json {
                        writeStringField("type", "route_chunk")
                        writeNumberField("revision", revision)
                        writeNumberField("offset", index * CHUNK_POINTS)
                        writeArrayFieldStart("geometry")
                        points.forEach { point(it) }
                        writeEndArray()
                    } + NEWLINE,
                )
            }
            add(
                json {
                    writeStringField("type", "route_end")
                    writeNumberField("revision", revision)
                } + NEWLINE,
            )
        }
        require(routeFrames.all { it.size <= MAX_FRAME_BYTES }) { "Oversized route chunk" }
    }

    private fun guidanceJson(state: GuidanceState, input: NavigationMirrorInput): String = json {
        writeFieldName("route_revision")
        if (routeJson == null) writeNull() else writeNumber(revision)
        writeBooleanField("route_unavailable", state.route != null && routeJson == null)
        stepField("next", state.nextStep)
        stepField("then", state.thenStep)
        nonnegative("distance_to_next_m", state.distToNextM)
        nonnegative("remaining_m", state.remainingM)
        nonnegative("remaining_s", state.remainingS)
        nonnegative("progress_m", state.s)
        nonnegative("uncertainty_m", state.uncertaintyM)
        writeStringField("travel_mode", state.travelMode.name)
        writeFieldName("speed_limit_kmh")
        state.speedLimitKmh?.takeIf { it in 1..MAX_SPEED_LIMIT }?.let { writeNumber(it) } ?: writeNull()
        writeStringField("source", state.source.name)
        writeStringField("gps_state", input.gpsState.name)
        writeFieldName("gps_verdict")
        input.verdict?.let { writeString(it.name) } ?: writeNull()
        writeBooleanField("jammed", input.jammed)
        writeBooleanField("off_route", state.offRoute)
        writeBooleanField("rerouting", state.rerouting)
        writeBooleanField("arrived", state.arrived)
    }.toString(Charsets.UTF_8)

    private fun json(write: JsonGenerator.() -> Unit): ByteArray = ByteArrayOutputStream().use { output ->
        factory.createGenerator(output).use { generator ->
            generator.writeStartObject()
            generator.write()
            generator.writeEndObject()
        }
        output.toByteArray()
    }

    private fun JsonGenerator.stepField(name: String, step: Step?) {
        writeFieldName(name)
        if (step == null) {
            writeNull()
            return
        }
        writeStartObject()
        writeStringField("kind", bounded(step.type, MAX_KIND_CHARS))
        writeFieldName("modifier")
        step.modifier?.let { writeString(bounded(it, MAX_KIND_CHARS)) } ?: writeNull()
        writeStringField("name", bounded(step.name, MAX_NAME_CHARS))
        writeFieldName("roundabout_exit")
        step.roundaboutExit?.takeIf { it in 0..MAX_EXIT }?.let { writeNumber(it) } ?: writeNull()
        writeEndObject()
    }

    private fun JsonGenerator.nonnegative(name: String, value: Double) = writeNumberField(name, value.takeIf { it.isFinite() && it >= 0 } ?: 0.0)
    private fun JsonGenerator.pointField(name: String, value: GeoPoint) {
        writeFieldName(name)
        point(value)
    }
    private fun JsonGenerator.point(value: GeoPoint) {
        writeStartArray()
        writeNumber(value.lat)
        writeNumber(value.lon)
        writeEndArray()
    }
    private fun validPoint(point: GeoPoint): Boolean = point.lat in -90.0..90.0 && point.lon in -180.0..180.0
    private fun bounded(value: String, characters: Int): String = value.take(characters)

    companion object {
        const val HANDSHAKE = "IMUNAV NAV/1\r\n"
        const val HELLO = "{\"type\":\"hello\",\"version\":1}\n"
        const val MAX_FRAME_BYTES = 16 * 1024
        const val MAX_ROUTE_BYTES = 1024 * 1024
        const val MAX_ROUTE_POINTS = 100_000
        private const val CHUNK_POINTS = 128
        private const val MAX_NAME_CHARS = 256 // At most 1024 UTF-8 bytes, including supplementary characters.
        private const val MAX_KIND_CHARS = 16
        private const val MAX_SPEED_LIMIT = 1_000
        private const val MAX_EXIT = 100
        private val NEWLINE = byteArrayOf(10)
    }
}
