package org.imunav.core.gnss

import org.imunav.core.geo.Geo
import org.imunav.core.geo.ServiceArea
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Stateful GPS trust boundary; Android uses Rust through JNI while JVM replay uses [TrustClassifier]. */
interface TrustEvaluator {
    fun reset()

    fun evaluate(fix: RawFix, lastGood: RawFix?, lastNet: RawFix?, gnss: GnssSnapshot, jammed: Boolean, compassDeg: Float?, wallNowMs: Long): Verdict
}

/** Stateful AGC jamming detector; Android uses Rust while JVM replay uses [JamDetector]. */
interface JammingDetector {
    val jammed: Boolean

    /** @return true when the jamming state changed. */
    fun update(agcDb: Float?, nowMs: Long): Boolean
}

/**
 * Plausibility checks on every GPS fix. Each failed check adds a reason to either the *hard* list
 * (any one ⇒ [TrustLevel.BAD]) or the *soft* list (⇒ [TrustLevel.SUSPECT]).
 *
 * The checks target what a spoofer or jammer cannot fake consistently: physics (jumps, speed
 * vs. displacement, frozen coordinates), the receiver (satellite count, flat C/N0 across
 * satellites, AGC level) and independent sources (network position, compass heading).
 */
class TrustClassifier(private val config: TrustConfig = TrustConfig(), private val area: ServiceArea = ServiceArea.EVERYWHERE) : TrustEvaluator {
    private var previousRaw: RawFix? = null
    private var frozenSinceMs = -1L
    private var jamStrongAtMs = -1L

    override fun reset() {
        previousRaw = null
        frozenSinceMs = -1L
        jamStrongAtMs = -1L
    }

    /**
     * @param lastGood last fix judged GOOD (for jump / speed consistency)
     * @param lastNet last network fix (independent position)
     * @param jammed current AGC jam state (with hysteresis) from [JamDetector]
     * @param compassDeg current device heading, if known
     * @param wallNowMs current wall-clock time, for clock-skew detection
     */
    override fun evaluate(fix: RawFix, lastGood: RawFix?, lastNet: RawFix?, gnss: GnssSnapshot, jammed: Boolean, compassDeg: Float?, wallNowMs: Long): Verdict {
        val r = Reasons()
        val prevRaw = previousRaw
        // Reject before changing sequence, frozen-position or jamming state.
        if (prevRaw != null && (fix.elapsedMs <= prevRaw.elapsedMs || fix.timeMs <= prevRaw.timeMs)) {
            return Verdict(TrustLevel.BAD, listOf("dup_time"))
        }
        checkFix(fix, gnss, wallNowMs, r)
        // An impossible wall clock must not advance the watermark and lock out valid fixes.
        if (r.hard.any { it.startsWith("clock_skew") }) return Verdict(TrustLevel.BAD, r.hard)
        previousRaw = fix
        checkAgainstLastGood(fix, lastGood, r)
        checkSequence(fix, prevRaw, r)
        checkNetwork(fix, lastNet, r)
        checkJamming(fix, lastNet, gnss, jammed, r)
        checkReceiver(fix, gnss, r)
        checkHeading(fix, compassDeg, r)

        return when {
            r.hard.isNotEmpty() -> {
                if (JAM_STRONG in r.soft) jamStrongAtMs = -1L
                Verdict(TrustLevel.BAD, r.hard + r.soft)
            }

            r.soft.isNotEmpty() -> Verdict(TrustLevel.SUSPECT, r.soft)

            else -> Verdict.GOOD
        }
    }

    /** Failed checks: any hard reason ⇒ BAD, only soft ones ⇒ SUSPECT. */
    private class Reasons {
        val hard = ArrayList<String>()
        val soft = ArrayList<String>()
    }

    /** Checks on the fix alone: mock flag, service area, altitude, speed, accuracy, clock. */
    private fun checkFix(fix: RawFix, gnss: GnssSnapshot, wallNowMs: Long, r: Reasons) {
        val c = config
        val speed = fix.speedMps
        val acc = fix.accuracyM
        if (!fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0 || invalidMeasurements(fix, gnss)) r.hard += "invalid"
        if (fix.isMock) r.hard += "mock"
        if (!area.contains(fix.lat, fix.lon)) r.hard += "outside_area"
        fix.altitudeM?.let { alt ->
            val slack = min(fix.verticalAccuracyM ?: 0f, 50f).toDouble()
            if (alt < c.altMinM - slack || alt > c.altMaxM + slack) r.hard += "alt=${alt.toInt()}"
        }
        if (speed != null && speed * 3.6 > c.maxSpeedKmh) r.hard += "speed=${(speed * 3.6).toInt()}"
        if (acc != null && acc > c.maxAccuracyM) r.hard += "acc=${acc.toInt()}"
        val skew = fix.timeMs - wallNowMs
        if (!timestampsWithin(fix.timeMs, wallNowMs, c.maxClockSkewMs)) r.hard += "clock_skew=${skew / 1000}s"
    }

    /** Missing values remain optional; supplied malformed measurements follow the Rust trust boundary. */
    private fun invalidMeasurements(fix: RawFix, gnss: GnssSnapshot): Boolean {
        val supplied = listOfNotNull(
            fix.altitudeM,
            fix.speedMps?.toDouble(),
            fix.bearingDeg?.toDouble(),
            fix.accuracyM?.toDouble(),
            fix.verticalAccuracyM?.toDouble(),
            gnss.meanCn0Used?.toDouble(),
            gnss.cn0SpreadUsed?.toDouble(),
            gnss.agcDb?.toDouble(),
        )
        return supplied.any { !it.isFinite() } || listOfNotNull(fix.speedMps, fix.accuracyM, fix.verticalAccuracyM).any { it < 0f }
    }

    /** Physics against the last trusted fix: accuracy jump, reachable distance, speed vs. displacement. */
    private fun checkAgainstLastGood(fix: RawFix, lastGood: RawFix?, r: Reasons) {
        val c = config
        val speed = fix.speedMps
        val acc = fix.accuracyM
        val prevGoodAcc = lastGood?.accuracyM
        if (acc != null && prevGoodAcc != null && acc > 15f && acc > prevGoodAcc * 3f) r.soft += "acc_jump=${acc.toInt()}"
        if (lastGood == null || fix.elapsedMs <= lastGood.elapsedMs) return
        val dt = (fix.elapsedMs - lastGood.elapsedMs) / 1000.0
        val dist = Geo.distance(lastGood.lat, lastGood.lon, fix.lat, fix.lon)
        val reachable = c.maxPlausibleSpeedMps * dt + (acc ?: 0f) + (prevGoodAcc ?: 0f) + 20.0
        if (dist > reachable) r.hard += "jump=${dist.toInt()}m/${dt.toInt()}s"
        if (speed != null && dt in 0.5..2.5) {
            val implied = dist / dt
            val mismatch = abs(speed - implied)
            if (mismatch > max(c.speedMismatchMinMps, max(speed.toDouble(), implied) * 0.5)) {
                r.soft += "speed_mismatch=${(speed * 3.6).toInt()}/${(implied * 3.6).toInt()}"
            }
        }
    }

    /** Against the previous raw fix: repeated timestamps, and frozen coordinates while "moving" (replayed / stuck spoofer). */
    private fun checkSequence(fix: RawFix, prevRaw: RawFix?, r: Reasons) {
        val c = config
        val speed = fix.speedMps
        val frozen = prevRaw != null && fix.lat == prevRaw.lat && fix.lon == prevRaw.lon && speed != null && speed > c.frozenMinSpeedMps
        if (prevRaw == null || !frozen) {
            frozenSinceMs = -1L
            return
        }
        if (frozenSinceMs < 0) frozenSinceMs = prevRaw.elapsedMs
        val frozenS = (fix.elapsedMs - frozenSinceMs) / 1000
        if (frozenS >= c.frozenBadS) {
            r.hard += "frozen=${frozenS}s"
        } else if (frozenS >= c.frozenSuspectS) {
            r.soft += "frozen=${frozenS}s"
        }
    }

    /** Disagreement with a fresh, accurate network fix. */
    private fun checkNetwork(fix: RawFix, lastNet: RawFix?, r: Reasons) {
        val c = config
        val speed = fix.speedMps
        val netAcc = lastNet?.accuracyM ?: return
        if (!validNetworkFix(lastNet)) return
        val fresh = timestampsWithin(fix.elapsedMs, lastNet.elapsedMs, 5000)
        val slowEnough = speed == null || speed < c.netDiffMaxSpeedMps
        if (netAcc >= c.netMaxAccM || !fresh || !slowEnough) return
        val d = Geo.distance(lastNet.lat, lastNet.lon, fix.lat, fix.lon)
        if (d > max(c.netDiffMinM, ((fix.accuracyM ?: 10f) + netAcc) * 3.0)) r.soft += "net_diff=${d.toInt()}m"
    }

    private fun gnssFresh(fix: RawFix, gnss: GnssSnapshot) = gnss.elapsedMs > 0 && gnss.elapsedMs <= fix.elapsedMs && fix.elapsedMs - gnss.elapsedMs < 5000

    /** AGC jamming: hard jamming makes the fix BAD unless the constellation looks healthy and is confirmed. */
    private fun checkJamming(fix: RawFix, lastNet: RawFix?, gnss: GnssSnapshot, jammed: Boolean, r: Reasons) {
        val c = config
        val fresh = gnssFresh(fix, gnss)
        val agc = gnss.agcDb
        if (fresh && agc != null && agc < c.jamHardAgcDb) {
            // A fix can still be real if the constellation looks healthy AND an independent source
            // agrees (or we accepted such a fix moments ago).
            if (healthyConstellation(gnss) && (networkAgrees(fix, lastNet) || chainedJamStrong(fix))) {
                r.soft += JAM_STRONG
                jamStrongAtMs = fix.elapsedMs
            } else {
                r.hard += "jam"
                jamStrongAtMs = -1L
            }
        } else if (jammed || (fresh && agc != null && agc < c.jamAgcDb)) {
            if (fresh && gnss.satellitesUsed < c.minSatsUsed) r.hard += "jam_weak" else r.soft += "jam_weak"
        }
    }

    private fun healthyConstellation(gnss: GnssSnapshot): Boolean {
        val c = config
        val minSats = if (gnss.dualFrequencyUsed >= 2) c.jamStrongMinSatsDual else c.jamStrongMinSats
        return gnss.satellitesUsed >= minSats &&
            (gnss.meanCn0Used ?: 0f) >= c.jamStrongMinCn0 &&
            (gnss.cn0SpreadUsed ?: 0f) >= c.jamStrongMinSpread
    }

    private fun networkAgrees(fix: RawFix, lastNet: RawFix?): Boolean {
        val c = config
        if (lastNet == null || !validNetworkFix(lastNet)) return false
        val netAcc = lastNet.accuracyM
        return (netAcc ?: Float.MAX_VALUE) <= c.jamStrongNetMaxAccM &&
            timestampsWithin(fix.elapsedMs, lastNet.elapsedMs, c.jamStrongNetMaxAgeMs) &&
            Geo.distance(lastNet.lat, lastNet.lon, fix.lat, fix.lon) <= c.jamStrongNetM + (fix.accuracyM ?: 10f) + (netAcc ?: 0f)
    }

    /** Malformed ancillary evidence must neither confirm nor discredit a satellite fix. */
    private fun validNetworkFix(fix: RawFix): Boolean = fix.lat in -90.0..90.0 &&
        fix.lon in -180.0..180.0 && fix.accuracyM?.let { it.isFinite() && it >= 0f } == true

    /** An overflowing non-negative difference becomes negative and must never appear fresh. */
    private fun timestampsWithin(firstMs: Long, secondMs: Long, maximumMs: Long): Boolean {
        val difference = if (firstMs >= secondMs) firstMs - secondMs else secondMs - firstMs
        return difference in 0..maximumMs
    }

    private fun chainedJamStrong(fix: RawFix) = jamStrongAtMs >= 0 && fix.elapsedMs - jamStrongAtMs in 1..config.jamStrongChainMs

    /** Receiver health: satellites used, signal strength and the spoofer's tell-tale flat C/N0. */
    private fun checkReceiver(fix: RawFix, gnss: GnssSnapshot, r: Reasons) {
        val c = config
        if (!gnssFresh(fix, gnss)) return
        if (gnss.satellitesUsed == 0 && gnss.satellitesVisible > 0) r.hard += "no_sats"
        if (gnss.satellitesUsed in 1 until c.minSatsUsed) r.soft += "sats=${gnss.satellitesUsed}"
        gnss.meanCn0Used?.let { if (it < c.minMeanCn0) r.soft += "cn0=${it.toInt()}" }
        gnss.cn0SpreadUsed?.let { if (gnss.satellitesUsed >= 4 && it < c.minCn0Spread) r.soft += "cn0_flat" }
    }

    /** GPS course vs. compass heading while moving. */
    private fun checkHeading(fix: RawFix, compassDeg: Float?, r: Reasons) {
        val c = config
        val bearing = fix.bearingDeg ?: return
        val speed = fix.speedMps ?: return
        if (compassDeg == null || speed <= c.headingCheckMinSpeedMps) return
        val diff = Geo.absAngleDiff(bearing.toDouble(), compassDeg.toDouble())
        if (diff > c.maxHeadingDiffDeg) r.soft += "heading_diff=${diff.toInt()}"
    }

    companion object {
        const val JAM_STRONG = "jam_strong"
    }
}

/**
 * AGC-based jamming state with hysteresis: enters below [enterDb], leaves only after staying
 * above [exitDb] for [exitHoldMs].
 */
class JamDetector(private val enterDb: Float = -12f, private val exitDb: Float = -8f, private val exitHoldMs: Long = 15_000) : JammingDetector {
    override var jammed = false
        private set
    private var aboveSinceMs = -1L
    private var lastSampleMs = -1L

    /** @return true if the state changed. */
    override fun update(agcDb: Float?, nowMs: Long): Boolean {
        if (agcDb == null || !agcDb.isFinite() || nowMs <= lastSampleMs) return false
        lastSampleMs = nowMs
        val before = jammed
        if (!jammed) {
            aboveSinceMs = -1L
            if (agcDb < enterDb) jammed = true
        } else if (agcDb < exitDb) {
            aboveSinceMs = -1L
        } else {
            if (aboveSinceMs < 0) aboveSinceMs = nowMs
            if (nowMs - aboveSinceMs >= exitHoldMs) {
                jammed = false
                aboveSinceMs = -1L
            }
        }
        return before != jammed
    }
}
