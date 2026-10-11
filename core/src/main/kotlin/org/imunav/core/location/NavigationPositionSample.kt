package org.imunav.core.location

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Immutable navigation output, never an additional positioning input or a statistical GPS accuracy. */
data class NavigationPositionSample(
    val point: GeoPoint,
    val speedMps: Double,
    val bearingDeg: Double,
    val uncertaintyM: Double,
    val source: PositionSource,
    val elapsedMs: Long,
    val utcMs: Long,
) {
    /** Reject missing, invalid and future samples before any transport sees them. */
    fun valid(): Boolean = point.lat in -90.0..90.0 && point.lon in -180.0..180.0 &&
        speedMps.isFinite() && speedMps >= 0 && speedMps <= 1_000 && bearingDeg in 0.0..<360.0 &&
        uncertaintyM.isFinite() && uncertaintyM >= 0 && source != PositionSource.NONE && elapsedMs >= 0 && utcMs in 0..MAX_UTC_MS

    fun fresh(nowMs: Long): Boolean = valid() && nowMs >= elapsedMs && nowMs - elapsedMs <= MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 2_000L
        const val MAX_UTC_MS = 4_102_444_799_999L // NMEA's two-digit year is unambiguous through 2099.

        /** Called immediately after an engine tick, using the tick's monotonic clock. */
        fun from(state: GuidanceState, elapsedMs: Long, utcMs: Long): NavigationPositionSample? {
            val point = state.position ?: return null
            if (!state.active || !state.bearingDeg.isFinite()) return null
            return NavigationPositionSample(
                point,
                state.speedKmh.toDouble() / 3.6,
                ((state.bearingDeg.toDouble() % 360) + 360) % 360,
                state.uncertaintyM,
                state.source,
                elapsedMs,
                utcMs,
            ).takeIf { it.valid() }
        }
    }
}

/** RMC alone advertises an estimated fix without inventing altitude, satellites or error statistics. */
object NavigationNmea {
    private val timeFormat = DateTimeFormatter.ofPattern("HHmmss.SSS", Locale.US).withZone(ZoneOffset.UTC)
    private val dateFormat = DateTimeFormatter.ofPattern("ddMMyy", Locale.US).withZone(ZoneOffset.UTC)

    fun encode(sample: NavigationPositionSample): String {
        require(sample.valid()) { "Invalid navigation sample" }
        val instant = Instant.ofEpochMilli(sample.utcMs)
        val latitude = coordinate(sample.point.lat, 2)
        val longitude = coordinate(sample.point.lon, 3)
        val body = String.format(
            Locale.US, "GPRMC,%s,A,%s,%s,%s,%s,%.3f,%.3f,%s,,,E",
            timeFormat.format(instant), latitude, if (sample.point.lat < 0) "S" else "N",
            longitude, if (sample.point.lon < 0) "W" else "E", sample.speedMps / 0.5144444444444445,
            sample.bearingDeg, dateFormat.format(instant),
        )
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return String.format(Locale.US, "$%s*%02X\r\n", body, checksum)
    }

    private fun coordinate(degrees: Double, width: Int): String {
        // Round the whole coordinate first so 59.99999995 minutes carries into the degree field.
        val units = (abs(degrees) * 60 * 1_000_000).roundToLong()
        val whole = units / 60_000_000
        val minutes = (units % 60_000_000).toDouble() / 1_000_000
        return String.format(Locale.US, "%0${width}d%09.6f", whole, minutes)
    }
}
