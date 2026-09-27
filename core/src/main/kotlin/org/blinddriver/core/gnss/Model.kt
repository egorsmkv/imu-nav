package org.blinddriver.core.gnss

import org.blinddriver.core.geo.GeoPoint

enum class FixSource { GPS, NET, FUSED }

/** A location fix as reported by the platform, before any trust decision. */
data class RawFix(
    val source: FixSource,
    /** Wall-clock UTC time of the fix, ms. */
    val timeMs: Long,
    /** Monotonic time of the fix (elapsedRealtime), ms. All engine timing uses this clock. */
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,
    val verticalAccuracyM: Float? = null,
    val speedAccuracyMps: Float? = null,
    val isMock: Boolean = false,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/** Aggregated satellite/receiver health, refreshed from GnssStatus and GnssMeasurements callbacks. */
data class GnssSnapshot(
    val satellitesVisible: Int = 0,
    val satellitesUsed: Int = 0,
    /** Mean C/N0 of satellites used in the fix, dB-Hz. */
    val meanCn0Used: Float? = null,
    /** Standard deviation of C/N0 across used satellites. A spoofer tends to make this flat. */
    val cn0SpreadUsed: Float? = null,
    val meanCn0Visible: Float? = null,
    /** Mean automatic gain control level, dB. Strongly negative = RF front end is fighting a jammer. */
    val agcDb: Float? = null,
    /** Number of used satellites on the L5/E5 band (< 1.5 GHz carrier). */
    val dualFrequencyUsed: Int = 0,
    /** elapsedRealtime of the last status update, ms (0 = never). */
    val elapsedMs: Long = 0,
)

enum class TrustLevel { GOOD, SUSPECT, BAD }

data class Verdict(val level: TrustLevel, val reasons: List<String>) {
    companion object {
        val GOOD = Verdict(TrustLevel.GOOD, emptyList())
    }
}

/** A fix together with the verdict it received. */
data class JudgedFix(val fix: RawFix, val verdict: Verdict)

enum class GpsState { OK, DEGRADED, LOST }

/** Thresholds of the spoofing / jamming classifier. */
data class TrustConfig(
    val altMinM: Double = -50.0,
    val altMaxM: Double = 2500.0,
    val maxSpeedKmh: Double = 150.0,
    val maxAccuracyM: Float = 100f,
    val maxClockSkewMs: Long = 30_000,
    val minSatsUsed: Int = 5,
    val minMeanCn0: Float = 20f,
    val minCn0Spread: Float = 1.5f,
    val maxHeadingDiffDeg: Float = 165f,
    val headingCheckMinSpeedMps: Float = 5.5f,
    val jamAgcDb: Float = -10f,
    val jamHardAgcDb: Float = -16f,
    val frozenMinSpeedMps: Float = 3f,
    val frozenSuspectS: Long = 5,
    val frozenBadS: Long = 15,
    val netDiffMinM: Double = 500.0,
    val netMaxAccM: Float = 100f,
    val netDiffMaxSpeedMps: Float = 8f,
    val speedMismatchMinMps: Double = 10.0,
    val jamStrongMinSats: Int = 8,
    val jamStrongMinSatsDual: Int = 6,
    val jamStrongMinCn0: Float = 25f,
    val jamStrongMinSpread: Float = 3f,
    val jamStrongNetM: Double = 150.0,
    val jamStrongNetMaxAgeMs: Long = 10_000,
    val jamStrongNetMaxAccM: Float = 150f,
    val jamStrongChainMs: Long = 3000,
    /** Max plausible vehicle speed used by jump gating, m/s (150 km/h). */
    val maxPlausibleSpeedMps: Double = 150.0 / 3.6,
)
