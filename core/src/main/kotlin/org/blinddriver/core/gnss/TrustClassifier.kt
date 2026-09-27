package org.blinddriver.core.gnss

import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.ServiceArea
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Plausibility checks on every GPS fix. Each failed check adds a reason to either the *hard* list
 * (any one ⇒ [TrustLevel.BAD]) or the *soft* list (⇒ [TrustLevel.SUSPECT]).
 *
 * The checks target what a spoofer or jammer cannot fake consistently: physics (jumps, speed
 * vs. displacement, frozen coordinates), the receiver (satellite count, flat C/N0 across
 * satellites, AGC level) and independent sources (network position, compass heading).
 */
class TrustClassifier(
    private val config: TrustConfig = TrustConfig(),
    private val area: ServiceArea = ServiceArea.EVERYWHERE,
) {
    private var previousRaw: RawFix? = null
    private var frozenSinceMs = -1L
    private var jamStrongAtMs = -1L

    fun reset() {
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
    fun evaluate(
        fix: RawFix,
        lastGood: RawFix?,
        lastNet: RawFix?,
        gnss: GnssSnapshot,
        jammed: Boolean,
        compassDeg: Float?,
        wallNowMs: Long,
    ): Verdict {
        val c = config
        val hard = ArrayList<String>()
        val soft = ArrayList<String>()
        val prevRaw = previousRaw
        previousRaw = fix
        val speed = fix.speedMps
        val acc = fix.accuracyM

        if (fix.isMock) hard += "mock"
        if (!area.contains(fix.lat, fix.lon)) hard += "outside_area"

        fix.altitudeM?.let { alt ->
            val slack = min(fix.verticalAccuracyM ?: 0f, 50f).toDouble()
            if (alt < c.altMinM - slack || alt > c.altMaxM + slack) hard += "alt=${alt.toInt()}"
        }
        if (speed != null && speed * 3.6 > c.maxSpeedKmh) hard += "speed=${(speed * 3.6).toInt()}"
        if (acc != null && acc > c.maxAccuracyM) hard += "acc=${acc.toInt()}"

        val skew = fix.timeMs - wallNowMs
        if (abs(skew) > c.maxClockSkewMs) hard += "clock_skew=${skew / 1000}s"

        val prevGoodAcc = lastGood?.accuracyM
        if (acc != null && prevGoodAcc != null && acc > 15f && acc > prevGoodAcc * 3f) soft += "acc_jump=${acc.toInt()}"

        if (lastGood != null && fix.elapsedMs > lastGood.elapsedMs) {
            val dt = (fix.elapsedMs - lastGood.elapsedMs) / 1000.0
            val dist = Geo.distance(lastGood.lat, lastGood.lon, fix.lat, fix.lon)
            val reachable = c.maxPlausibleSpeedMps * dt + (acc ?: 0f) + (prevGoodAcc ?: 0f) + 20.0
            if (dist > reachable) hard += "jump=${dist.toInt()}m/${dt.toInt()}s"
            if (speed != null && dt in 0.5..2.5) {
                val implied = dist / dt
                val mismatch = abs(speed - implied)
                if (mismatch > max(c.speedMismatchMinMps, max(speed.toDouble(), implied) * 0.5)) {
                    soft += "speed_mismatch=${(speed * 3.6).toInt()}/${(implied * 3.6).toInt()}"
                }
            }
        }

        if (prevRaw != null && (fix.elapsedMs <= prevRaw.elapsedMs || fix.timeMs <= prevRaw.timeMs)) hard += "dup_time"

        // Frozen coordinates while the fix claims we are moving: a replayed / stuck spoofer.
        if (prevRaw != null && fix.lat == prevRaw.lat && fix.lon == prevRaw.lon &&
            speed != null && speed > c.frozenMinSpeedMps
        ) {
            if (frozenSinceMs < 0) frozenSinceMs = prevRaw.elapsedMs
            val frozenS = (fix.elapsedMs - frozenSinceMs) / 1000
            if (frozenS >= c.frozenBadS) hard += "frozen=${frozenS}s" else if (frozenS >= c.frozenSuspectS) soft += "frozen=${frozenS}s"
        } else {
            frozenSinceMs = -1L
        }

        // Disagreement with a fresh, accurate network fix.
        val netAcc = lastNet?.accuracyM
        if (lastNet != null && netAcc != null && netAcc < c.netMaxAccM &&
            abs(fix.elapsedMs - lastNet.elapsedMs) <= 5000 &&
            (speed == null || speed < c.netDiffMaxSpeedMps)
        ) {
            val d = Geo.distance(lastNet.lat, lastNet.lon, fix.lat, fix.lon)
            if (d > max(c.netDiffMinM, ((acc ?: 10f) + netAcc) * 3.0)) soft += "net_diff=${d.toInt()}m"
        }

        val gnssFresh = gnss.elapsedMs > 0 && fix.elapsedMs - gnss.elapsedMs < 5000
        val agc = gnss.agcDb
        if (gnssFresh && agc != null && agc < c.jamHardAgcDb) {
            // Hard jamming. A fix can still be real if the constellation looks healthy AND an
            // independent source agrees (or we accepted such a fix moments ago).
            val minSats = if (gnss.dualFrequencyUsed >= 2) c.jamStrongMinSatsDual else c.jamStrongMinSats
            val healthy = gnss.satellitesUsed >= minSats &&
                (gnss.meanCn0Used ?: 0f) >= c.jamStrongMinCn0 &&
                (gnss.cn0SpreadUsed ?: 0f) >= c.jamStrongMinSpread
            val netAgrees = lastNet != null && (netAcc ?: Float.MAX_VALUE) <= c.jamStrongNetMaxAccM &&
                abs(fix.elapsedMs - lastNet.elapsedMs) <= c.jamStrongNetMaxAgeMs &&
                Geo.distance(lastNet.lat, lastNet.lon, fix.lat, fix.lon) <= c.jamStrongNetM + (acc ?: 10f) + (netAcc ?: 0f)
            val chained = jamStrongAtMs >= 0 && fix.elapsedMs - jamStrongAtMs in 1..c.jamStrongChainMs
            if (healthy && (netAgrees || chained)) {
                soft += JAM_STRONG
                jamStrongAtMs = fix.elapsedMs
            } else {
                hard += "jam"
                jamStrongAtMs = -1L
            }
        } else if (jammed || (gnssFresh && agc != null && agc < c.jamAgcDb)) {
            if (gnssFresh && gnss.satellitesUsed < c.minSatsUsed) hard += "jam_weak" else soft += "jam_weak"
        }

        if (gnssFresh) {
            if (gnss.satellitesUsed == 0 && gnss.satellitesVisible > 0) hard += "no_sats"
            if (gnss.satellitesUsed in 1 until c.minSatsUsed) soft += "sats=${gnss.satellitesUsed}"
            gnss.meanCn0Used?.let { if (it < c.minMeanCn0) soft += "cn0=${it.toInt()}" }
            gnss.cn0SpreadUsed?.let { if (gnss.satellitesUsed >= 4 && it < c.minCn0Spread) soft += "cn0_flat" }
        }

        val bearing = fix.bearingDeg
        if (compassDeg != null && bearing != null && speed != null && speed > c.headingCheckMinSpeedMps) {
            val diff = Geo.absAngleDiff(bearing.toDouble(), compassDeg.toDouble())
            if (diff > c.maxHeadingDiffDeg) soft += "heading_diff=${diff.toInt()}"
        }

        return when {
            hard.isNotEmpty() -> {
                if (JAM_STRONG in soft) jamStrongAtMs = -1L
                Verdict(TrustLevel.BAD, hard + soft)
            }
            soft.isNotEmpty() -> Verdict(TrustLevel.SUSPECT, soft)
            else -> Verdict.GOOD
        }
    }

    companion object {
        const val JAM_STRONG = "jam_strong"
    }
}

/**
 * AGC-based jamming state with hysteresis: enters below [enterDb], leaves only after staying
 * above [exitDb] for [exitHoldMs].
 */
class JamDetector(
    private val enterDb: Float = -12f,
    private val exitDb: Float = -8f,
    private val exitHoldMs: Long = 15_000,
) {
    var jammed = false
        private set
    private var aboveSinceMs = -1L

    /** @return true if the state changed. */
    fun update(agcDb: Float?, nowMs: Long): Boolean {
        if (agcDb == null) return false
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
