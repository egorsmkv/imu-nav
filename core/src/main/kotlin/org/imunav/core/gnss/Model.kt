package org.imunav.core.gnss

import org.imunav.core.geo.GeoPoint

/** Where a location fix came from. */
enum class FixSource {
    /** Satellite receiver (GPS, Galileo, GLONASS, BeiDou…). The one that gets jammed and spoofed. */
    GPS,

    /** Android's network location (Wi-Fi / cell towers, computed by the phone vendor or Google). */
    NET,

    /** Android's fused provider. Built mostly from GPS, so it inherits spoofed positions; not trusted. */
    FUSED,

    /** Computed on-device from visible cell towers and an offline tower database. */
    CELL,
}

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

/**
 * Health of the satellite receiver, refreshed from Android's GnssStatus / GnssMeasurements callbacks.
 *
 * Two terms used below:
 *  - **C/N0** (carrier-to-noise density, dB-Hz): how strong a satellite's signal is. Real signals
 *    differ a lot between satellites (high ones strong, low ones weak); a spoofer broadcasting all
 *    of them from one antenna makes them suspiciously similar.
 *  - **AGC** (automatic gain control, dB): how much the receiver turns its amplifier down. A jammer
 *    floods the band with noise, so the AGC drops far below normal.
 */
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

/** How much a GPS fix can be believed. */
enum class TrustLevel {
    /** Passed every check: use it. */
    GOOD,

    /** Something looks odd: use only if it agrees with our own estimate. */
    SUSPECT,

    /** Failed a hard check (impossible jump, mock location, jamming…): ignore it. */
    BAD,
}

/** The classifier's decision about one fix, with short machine-readable reasons (e.g. "jump=900m/2s"). */
data class Verdict(val level: TrustLevel, val reasons: List<String>) {
    companion object {
        val GOOD = Verdict(TrustLevel.GOOD, emptyList())
    }
}

/** A fix together with the verdict it received. */
data class JudgedFix(val fix: RawFix, val verdict: Verdict)

/** Summary of recent GPS quality, shown in the UI. */
enum class GpsState { OK, DEGRADED, LOST }

/**
 * Thresholds of the spoofing / jamming classifier ([TrustClassifier]). The defaults were tuned for
 * cars in Ukraine; change them only with recorded trips to test against (see the `replay` tool).
 */
data class TrustConfig(
    // --- Checks on the fix alone (hard: fail ⇒ BAD)
    /** Plausible altitude range for a car, metres (Ukraine: sea level … Carpathian passes). */
    val altMinM: Double = -50.0,
    val altMaxM: Double = 2500.0,
    /** Faster than this is not a car. */
    val maxSpeedKmh: Double = 150.0,
    /** Fixes claiming worse accuracy than this are useless for navigation. */
    val maxAccuracyM: Float = 100f,
    /** The fix's timestamp may differ from the phone clock by at most this (spoofers often get time wrong). */
    val maxClockSkewMs: Long = 30_000,

    // --- Receiver health (soft: fail ⇒ SUSPECT)
    /** Fewer satellites than this in the fix is weak. */
    val minSatsUsed: Int = 5,
    /** Average signal strength below this (dB-Hz) is weak. */
    val minMeanCn0: Float = 20f,
    /** Signal strengths varying less than this across satellites looks spoofed ("flat C/N0"). */
    val minCn0Spread: Float = 1.5f,

    // --- GPS course vs. compass
    /** GPS says we drive one way, the compass the opposite way (beyond this angle). */
    val maxHeadingDiffDeg: Float = 165f,
    /** Only compare while faster than this (a slow GPS course is noisy). */
    val headingCheckMinSpeedMps: Float = 5.5f,

    // --- Jamming (AGC, dB)
    /** AGC below this = weak jamming. */
    val jamAgcDb: Float = -10f,
    /** AGC below this = hard jamming: the fix is BAD unless the "strong receiver" rules below save it. */
    val jamHardAgcDb: Float = -16f,

    // --- Frozen coordinates (a stuck or replaying spoofer)
    /** Only counts while the fix claims at least this speed. */
    val frozenMinSpeedMps: Float = 3f,
    /** Same coordinates for this long ⇒ SUSPECT, then ⇒ BAD. */
    val frozenSuspectS: Long = 5,
    val frozenBadS: Long = 15,

    // --- Agreement with the independent network position
    /** GPS and network further apart than this (and than their accuracies allow) ⇒ SUSPECT. */
    val netDiffMinM: Double = 500.0,
    /** Only trust network fixes at least this accurate for the comparison. */
    val netMaxAccM: Float = 100f,
    /** Only compare below this speed (network fixes lag behind a fast car). */
    val netDiffMaxSpeedMps: Float = 8f,
    /** Reported speed vs. speed implied by the jump from the last fix may differ by this much. */
    val speedMismatchMinMps: Double = 10.0,

    // --- "Strong receiver" exception during hard jamming: a fix is still accepted when the
    //     constellation looks healthy AND the network position agrees (or we accepted one moments ago).
    val jamStrongMinSats: Int = 8,
    /** Dual-frequency receivers are harder to jam, so fewer satellites suffice. */
    val jamStrongMinSatsDual: Int = 6,
    val jamStrongMinCn0: Float = 25f,
    val jamStrongMinSpread: Float = 3f,
    /** Network fix within this distance (plus accuracies) counts as agreeing. */
    val jamStrongNetM: Double = 150.0,
    val jamStrongNetMaxAgeMs: Long = 10_000,
    val jamStrongNetMaxAccM: Float = 150f,
    /** After one accepted fix, the next ones within this time are accepted on the same grounds. */
    val jamStrongChainMs: Long = 3000,

    // --- Jump check
    /** Max plausible vehicle speed used by jump gating, m/s (150 km/h). */
    val maxPlausibleSpeedMps: Double = 150.0 / 3.6,
)
