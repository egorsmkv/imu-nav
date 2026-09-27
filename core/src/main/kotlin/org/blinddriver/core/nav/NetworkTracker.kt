package org.blinddriver.core.nav

import org.blinddriver.core.speed.MAX_SPEED_MPS
import org.blinddriver.core.speed.NetSpeedEstimator
import kotlin.math.abs
import kotlin.math.max

/** A network fix projected onto the route. */
data class NetSample(val elapsedMs: Long, val s: Double, val accM: Double, val offsetM: Double)

/**
 * Keeps route-projected network fixes honest.
 *
 * Gate: a new fix is accepted only if it is reachable from the last accepted anchor at
 * 150 km/h (|Δs| ≤ vmax·Δt + acc₁ + acc₂). Rejected fixes become *candidates*; if at least two
 * mutually consistent candidates span ≥ 12 s and do not move backwards faster than 6 m/s, the
 * anchor was wrong and the tracker re-anchors on them.
 */
class NetworkTracker {
    private class Anchor(val tS: Double, val s: Double, val acc: Double)

    enum class GateResult { ACCEPTED, REANCHORED, REJECTED }

    private var anchor: Anchor? = null
    private val candidates = ArrayList<Anchor>()

    /** Latest accepted samples (max 4), used for band correction and turn release. */
    val recent = ArrayList<NetSample>()

    /** Accepted samples of the last 30 s, used by the "marker ran ahead" check. */
    val history = ArrayList<NetSample>()

    val speed = NetSpeedEstimator()

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun reset() {
        anchor = null
        candidates.clear()
        recent.clear()
        history.clear()
        speed.clear()
        lastLat = Double.NaN
        lastLon = Double.NaN
    }

    /** Drop collected samples but keep the gate anchor (after a re-anchor). */
    fun clearSamples() {
        recent.clear()
        history.clear()
        speed.clear()
    }

    fun gate(elapsedMs: Long, s: Double, acc: Double): GateResult {
        val t = elapsedMs / 1000.0
        val a = anchor
        if (acc > 300.0) {
            val ok = a != null && reachable(a, t, s, acc)
            return if (ok) GateResult.ACCEPTED else GateResult.REJECTED
        }
        if (a == null) {
            anchor = Anchor(t, s, acc)
            return GateResult.ACCEPTED
        }
        if (candidates.isNotEmpty()) {
            if (reachable(candidates.last(), t, s, acc)) {
                candidates += Anchor(t, s, acc)
                val span = candidates.last().tS - candidates.first().tS
                if (candidates.size >= 2 && span >= 12.0 && slope(candidates) >= -6.0) {
                    anchor = Anchor(t, s, acc)
                    candidates.clear()
                    return GateResult.REANCHORED
                }
                return GateResult.REJECTED
            }
        }
        if (reachable(a, t, s, acc)) {
            anchor = Anchor(t, s, acc)
            candidates.clear()
            return GateResult.ACCEPTED
        }
        candidates.clear()
        candidates += Anchor(t, s, acc)
        return GateResult.REJECTED
    }

    private fun reachable(from: Anchor, t: Double, s: Double, acc: Double): Boolean =
        abs(s - from.s) <= max(0.0, t - from.tS) * MAX_SPEED_MPS + from.acc + acc

    private fun slope(points: List<Anchor>): Double {
        val tMean = points.sumOf { it.tS } / points.size
        val sMean = points.sumOf { it.s } / points.size
        val stt = points.sumOf { (it.tS - tMean) * (it.tS - tMean) }
        if (stt < 1e-9) return 0.0
        return points.sumOf { (it.tS - tMean) * (it.s - sMean) } / stt
    }

    /** Record an accepted sample. Duplicate coordinates (cached fixes) do not feed speed. */
    fun record(sample: NetSample, lat: Double, lon: Double) {
        recent += sample
        while (recent.size > 4) recent.removeAt(0)
        val duplicate = lat == lastLat && lon == lastLon
        lastLat = lat
        lastLon = lon
        if (duplicate) return
        if (sample.accM <= 120.0) speed.add(sample.s, sample.accM, sample.elapsedMs)
        history += sample
    }

    fun pruneHistory(nowMs: Long) {
        history.removeAll { nowMs - it.elapsedMs > 30_000 }
    }

    /** True if the last two accepted samples are physically consistent with each other. */
    fun lastTwoConsistent(): Boolean {
        if (recent.size < 2) return false
        val a = recent[recent.size - 2]
        val b = recent[recent.size - 1]
        val dt = (b.elapsedMs - a.elapsedMs) / 1000.0
        return dt > 0 && abs(b.s - a.s) <= dt * MAX_SPEED_MPS + a.accM + b.accM
    }
}

/**
 * Pending "you seem to have left the route" offer. While [pendingUntilMs] ≥ 0 a countdown runs;
 * when it expires the engine reroutes on its own. After any outcome, new offers are suppressed
 * until [cooldownUntilMs].
 */
class DeviationOffer {
    var pendingUntilMs = -1L
    var cooldownUntilMs = 0L

    val pending: Boolean get() = pendingUntilMs >= 0

    fun canOffer(nowMs: Long): Boolean = !pending && nowMs >= cooldownUntilMs

    fun clear(nowMs: Long, cooldownMs: Long) {
        pendingUntilMs = -1L
        cooldownUntilMs = max(cooldownUntilMs, nowMs + cooldownMs)
    }
}
