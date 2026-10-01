package org.imunav.core.nav

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Moves the Kotlin marker smoothly towards a GPS anchor extrapolated with GPS speed: never backwards
 * by less than 25 m, and forward with a 0.4 s time constant. Native mode does not use this smoother.
 */
internal class MarkerSmoother {
    var valid = false
    private var anchorS = 0.0
    private var anchorMs = 0L
    private var speed = 0.0

    fun anchor(s: Double, elapsedMs: Long, speedMps: Double) {
        anchorS = s
        anchorMs = elapsedMs
        speed = speedMps
        valid = true
    }

    fun follow(current: Double, nowMs: Long, dt: Double, snap: Boolean): Double {
        if (!valid) return current
        val predicted = anchorS + speed * ((nowMs - anchorMs) / 1000.0).coerceIn(0.0, 1.6)
        val diff = predicted - current
        if (snap || abs(diff) > 25.0) return predicted
        if (diff > 0) return current + (1.0 - exp(-max(dt, 0.0) / 0.4)) * diff
        return current
    }
}

/**
 * Tracks recovery for the UI and Kotlin's relaxed SUSPECT-fix gate after GPS loss/jamming.
 * Native mode retains its own innovation gates; this indicator never relaxes native covariance tests.
 */
internal class JamRecovery {
    private var untilMs = 0L
    private var wasJammed = false
    private var wasLost = false
    private var goodInRow = 0

    fun update(jammed: Boolean, lost: Boolean, nowMs: Long) {
        if ((wasJammed && !jammed) || (wasLost && !lost)) {
            untilMs = nowMs + 300_000
            goodInRow = 0
        }
        wasJammed = jammed
        wasLost = lost
    }

    fun onFix(good: Boolean) {
        goodInRow = if (good) goodInRow + 1 else 0
    }

    fun active(nowMs: Long): Boolean = nowMs < untilMs && goodInRow < 10
}
