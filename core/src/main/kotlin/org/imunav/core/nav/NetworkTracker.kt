package org.imunav.core.nav

import org.imunav.core.speed.MAX_SPEED_MPS
import org.imunav.core.speed.NetSpeedEstimator
import org.imunav.core.speed.SpeedEstimate
import kotlin.math.abs
import kotlin.math.max

/** A network fix projected onto the route. */
data class NetSample(val elapsedMs: Long, val s: Double, val accM: Double, val offsetM: Double)

/** Network-position math boundary; Android uses Rust while JVM replay uses [NetworkTracker]. */
interface NetworkPositionTracker {
    val recent: List<NetSample>
    val history: List<NetSample>

    fun reset()
    fun clearSamples()
    fun gate(elapsedMs: Long, s: Double, acc: Double): NetworkTracker.GateResult
    fun record(sample: NetSample, lat: Double, lon: Double)
    fun pruneHistory(nowMs: Long)
    fun lastTwoConsistent(): Boolean
    fun speedEstimate(nowMs: Long): SpeedEstimate?
    fun strictSpeedEstimate(nowMs: Long): SpeedEstimate?
}

/**
 * Keeps route-projected network fixes honest.
 *
 * Gate: a new fix is accepted only if it is reachable from the last accepted anchor at
 * 150 km/h (|Δs| ≤ vmax·Δt + acc₁ + acc₂). Rejected fixes become *candidates*; if at least two
 * mutually consistent candidates span ≥ 12 s and do not move backwards faster than 6 m/s, the
 * anchor was wrong and the tracker re-anchors on them.
 */
class NetworkTracker : NetworkPositionTracker {
    /** A trusted reference point: route position [s] at time [tS] (seconds), accurate to [acc] metres. */
    private class Anchor(val tS: Double, val s: Double, val acc: Double)

    enum class GateResult {
        /** Consistent with what we believed: use it. */
        ACCEPTED,

        /** Our old anchor was wrong; the tracker switched to the new, consistent fixes. */
        REANCHORED,

        /** Physically impossible from the anchor: ignore it (for now). */
        REJECTED,
    }

    private var anchor: Anchor? = null
    private val candidates = ArrayList<Anchor>()

    /** Latest accepted samples (max 4), used for band correction and turn release. */
    override val recent = ArrayList<NetSample>()

    /** Accepted samples of the last 30 s, used by the "marker ran ahead" check. */
    override val history = ArrayList<NetSample>()

    val speed = NetSpeedEstimator()

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    override fun reset() {
        anchor = null
        candidates.clear()
        recent.clear()
        history.clear()
        speed.clear()
        lastLat = Double.NaN
        lastLon = Double.NaN
    }

    /** Drop collected samples but keep the gate anchor (after a re-anchor). */
    override fun clearSamples() {
        recent.clear()
        history.clear()
        speed.clear()
    }

    /** Decide whether a network fix at route position [s] (accuracy [acc] m) can be believed. */
    override fun gate(elapsedMs: Long, s: Double, acc: Double): GateResult {
        if (elapsedMs < 0 || !s.isFinite() || !acc.isFinite() || acc < 0.0) return GateResult.REJECTED
        val timeS = elapsedMs / 1000.0
        val current = anchor
        val fix = Anchor(timeS, s, acc)

        // A stale fix cannot rewind an anchor or restart a newer confirmation chain.
        if (current != null && timeS <= current.tS) return GateResult.REJECTED
        if (candidates.lastOrNull()?.let { timeS <= it.tS } == true) return GateResult.REJECTED

        // Very coarse fixes may confirm the anchor, but never replace it.
        if (acc > COARSE_ACCURACY_M) {
            val ok = current != null && reachable(current, fix)
            return if (ok) GateResult.ACCEPTED else GateResult.REJECTED
        }
        if (current == null) {
            anchor = fix
            return GateResult.ACCEPTED
        }
        // Collecting evidence that the anchor itself was wrong?
        if (candidates.isNotEmpty() && reachable(candidates.last(), fix)) {
            candidates += fix
            val spanS = candidates.last().tS - candidates.first().tS
            val movesForward = slope(candidates) >= MAX_BACKWARDS_MPS
            if (candidates.size >= 2 && spanS >= REANCHOR_MIN_SPAN_S && movesForward) {
                anchor = fix
                candidates.clear()
                return GateResult.REANCHORED
            }
            return GateResult.REJECTED
        }
        if (reachable(current, fix)) {
            anchor = fix
            candidates.clear()
            return GateResult.ACCEPTED
        }
        // Impossible from the anchor: remember it as the first candidate of a possible re-anchor.
        candidates.clear()
        candidates += fix
        return GateResult.REJECTED
    }

    /** Could a car get from [from] to [to] at 150 km/h, allowing for both fixes' inaccuracy? */
    private fun reachable(from: Anchor, to: Anchor): Boolean = abs(to.s - from.s) <= max(0.0, to.tS - from.tS) * MAX_SPEED_MPS + from.acc + to.acc

    /** Speed (m/s) of the least-squares line through the points. */
    private fun slope(points: List<Anchor>): Double {
        val tMean = points.sumOf { it.tS } / points.size
        val sMean = points.sumOf { it.s } / points.size
        val stt = points.sumOf { (it.tS - tMean) * (it.tS - tMean) }
        if (stt < 1e-9) return 0.0
        return points.sumOf { (it.tS - tMean) * (it.s - sMean) } / stt
    }

    /** Record an accepted sample. Duplicate coordinates (cached fixes) do not feed speed. */
    override fun record(sample: NetSample, lat: Double, lon: Double) {
        recent += sample
        while (recent.size > 4) recent.removeAt(0)
        val duplicate = lat == lastLat && lon == lastLon
        lastLat = lat
        lastLon = lon
        if (duplicate) return
        if (sample.accM <= 120.0) speed.add(sample.s, sample.accM, sample.elapsedMs)
        history += sample
    }

    override fun pruneHistory(nowMs: Long) {
        history.removeAll { nowMs - it.elapsedMs > 30_000 }
    }

    /** True if the last two accepted samples are physically consistent with each other. */
    override fun lastTwoConsistent(): Boolean {
        if (recent.size < 2) return false
        val older = recent[recent.size - 2]
        val newer = recent[recent.size - 1]
        val dtS = (newer.elapsedMs - older.elapsedMs) / 1000.0
        return dtS > 0 && abs(newer.s - older.s) <= dtS * MAX_SPEED_MPS + older.accM + newer.accM
    }

    override fun speedEstimate(nowMs: Long) = speed.estimate(nowMs)

    override fun strictSpeedEstimate(nowMs: Long) = speed.strictEstimate(nowMs)

    private companion object {
        const val COARSE_ACCURACY_M = 300.0
        const val REANCHOR_MIN_SPAN_S = 12.0

        /** Network fixes wobble; a real car does not drive backwards along the route faster than this. */
        const val MAX_BACKWARDS_MPS = -6.0
    }
}

/**
 * Pending "you seem to have left the route" offer. While [pendingUntilMs] ≥ 0 a countdown runs;
 * when it expires the engine reroutes on its own. After any outcome, new offers are suppressed
 * until [cooldownUntilMs].
 */
class DeviationOffer {
    /** When the countdown ends (-1 = no offer running). */
    var pendingUntilMs = -1L

    /** No new offers before this time. */
    var cooldownUntilMs = 0L

    val pending: Boolean get() = pendingUntilMs >= 0

    fun canOffer(nowMs: Long): Boolean = !pending && nowMs >= cooldownUntilMs

    fun clear(nowMs: Long, cooldownMs: Long) {
        pendingUntilMs = -1L
        cooldownUntilMs = max(cooldownUntilMs, nowMs + cooldownMs)
    }
}
