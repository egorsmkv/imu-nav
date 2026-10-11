package org.imunav.core.util

/** Monotonic fixed-rate schedule: work consumes its interval and overruns skip missed ticks without catch-up bursts. */
class PeriodicDeadline(startMs: Long, private val periodMs: Long) {
    private var nextMs = startMs

    init {
        require(periodMs > 0)
    }

    /** Call after work; return the remaining delay to the next future deadline. */
    fun delayAfterWork(nowMs: Long): Long {
        nextMs += periodMs
        if (nextMs <= nowMs) nextMs += ((nowMs - nextMs) / periodMs + 1) * periodMs
        return nextMs - nowMs
    }
}
