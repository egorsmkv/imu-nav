package org.imunav.core.nav

import org.imunav.core.gnss.RawFix
import org.imunav.core.route.RouteCursor
import org.imunav.core.speed.MAX_SPEED_MPS
import kotlin.math.max
import kotlin.math.min

/** Holds gradual network corrections and their timing across navigation ticks. */
internal class NetworkCorrection(private val net: NetworkPositionTracker, private val log: (String) -> Unit) {
    private var catchUp = 0.0
    private var lastCatchupFixMs = -1L
    private var lastNetBackMs = -30_000L

    /** A stronger anchor or route change invalidates the scheduled catch-up. */
    fun clearCatchUp() {
        catchUp = 0.0
    }

    /**
     * Keep the marker within [s_net ± (acc + 300)] of the latest consistent network fix, and
     * when it lags, schedule a Kalman-style catch-up: gain = σ_dr² / (σ_dr² + (acc+30)²),
     * σ_dr = min(600, 25 + 0.08·distance dead-reckoned).
     */
    fun bandCorrection(car: RouteCursor, fix: RawFix?, nowMs: Long, dt: Double, stopped: Boolean, drDriftM: Double, holdS: Double) {
        val latest = net.recent.lastOrNull() ?: return
        if (fix == null || latest.elapsedMs != fix.elapsedMs || nowMs - latest.elapsedMs >= 30_000) return
        if (!net.lastTwoConsistent()) return
        val band = latest.accM + 300.0
        val maxStep = MAX_SPEED_MPS * dt
        if (!stopped && car.s > latest.s + band) car.moveTo(max(latest.s + band, car.s - maxStep))
        if (car.s < latest.s - band) {
            var target = min(car.s + maxStep, latest.s - band)
            if (holdS > car.s) target = min(target, holdS)
            car.moveTo(target)
        }
        if (!stopped && latest.elapsedMs != lastCatchupFixMs) {
            lastCatchupFixMs = latest.elapsedMs
            val gap = latest.s - car.s
            if (latest.accM <= 100.0 && gap > 0) {
                // How much to trust the network fix vs. our own estimate, like a Kalman filter:
                // the longer we dead-reckoned (bigger sigmaDr), the more of the gap we close.
                val sigmaDr = min(600.0, 25.0 + drDriftM)
                val sigmaNet = latest.accM + 30.0
                val gain = gap * sigmaDr * sigmaDr / (sigmaDr * sigmaDr + sigmaNet * sigmaNet)
                if (gain > 1.0) catchUp = gain
            }
        }
    }

    /** Close the scheduled [catchUp] gradually (at most 15 m/s extra) instead of jumping. */
    fun applyCatchUp(car: RouteCursor, dt: Double, holdS: Double) {
        if (catchUp <= 0.0 || car.s >= holdS) return
        val step = minOf(catchUp, 15.0 * dt, holdS - car.s)
        car.advance(step)
        catchUp -= step
    }

    /** If the marker has run far ahead of every recent network fix, pull it back to their weighted mean. */
    fun netBack(car: RouteCursor, nowMs: Long, currentSpeed: Double, confirmedTurnS: Double?) {
        net.pruneHistory(nowMs)
        if (nowMs - lastNetBackMs < 30_000 || net.history.isEmpty()) return
        val usable = net.history.filter { nowMs - it.elapsedMs in 0..30_000 && it.accM <= 150.0 }
        if (usable.size < 3) return
        val predicted = usable.map { it.s + currentSpeed * (nowMs - it.elapsedMs) / 1000.0 }
        if (usable.indices.any { car.s - predicted[it] < max(200.0, usable[it].accM * 2.0) }) return
        val weights = usable.map { 1.0 / (it.accM * it.accM) }
        val mean = usable.indices.sumOf { weights[it] * predicted[it] } / weights.sum()
        var target = max(mean, car.s - 300.0)
        confirmedTurnS?.let { target = max(target, it) }
        if (target >= car.s) return
        lastNetBackMs = nowMs
        log("net_back from_s=${car.s.toInt()} to_s=${target.toInt()} n=${usable.size}")
        car.moveTo(target)
        catchUp = 0.0
    }
}
