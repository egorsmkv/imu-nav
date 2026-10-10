package org.imunav.core.imu

/**
 * Walking speed from the phone's step detector: steps per second × stride length.
 *
 * Android's step detector reports one event per step (it works with the phone in a hand, pocket
 * or bag). The stride starts at a typical adult value and is learned while GPS is trusted:
 * stride = GPS speed / step rate.
 */
class Pedometer {
    /** Times of recent steps (elapsed ms), the last [WINDOW_MS]. */
    private val steps = ArrayDeque<Long>()

    /** Learned stride length, meters. */
    var strideM = DEFAULT_STRIDE_M
        private set

    /** True once the step sensor delivered anything this trip (some phones have none). */
    var available = false
        private set

    /** Last real step bounds extrapolation; reading cadence must never refresh this deadline. */
    val movingUntilMs: Long get() = (steps.lastOrNull() ?: 0L) + STOPPED_AFTER_MS

    /** Forget the steps of a previous trip (the learned stride is kept). */
    fun reset() {
        steps.clear()
        available = false
    }

    /** One step detected at [elapsedMs]. */
    fun onStep(elapsedMs: Long) {
        if (elapsedMs < 0 || steps.lastOrNull()?.let { elapsedMs <= it } == true) return
        available = true
        steps.addLast(elapsedMs)
        while (steps.isNotEmpty() && elapsedMs - steps.first() > WINDOW_MS) steps.removeFirst()
    }

    /** Steps per second over the recent window, 0 if the user stopped; null without a step sensor. */
    fun cadence(nowMs: Long): Double? {
        if (!available) return null
        val last = steps.lastOrNull { it <= nowMs } ?: return 0.0
        if (nowMs - last > STOPPED_AFTER_MS) return 0.0 // no step for a while: standing
        val recent = steps.count { it <= nowMs && nowMs - it <= WINDOW_MS }
        if (recent < 2) return 0.0
        val spanS = (last - steps.first { it <= nowMs && nowMs - it <= WINDOW_MS }) / 1000.0
        return if (spanS <= 0) 0.0 else (recent - 1) / spanS
    }

    /** Walking speed, m/s (0 when standing); null without a step sensor. */
    fun speed(nowMs: Long): Double? = cadence(nowMs)?.let { it * strideM }

    /**
     * Refine the stride from a trusted GPS speed. Only a steady walk counts (1–3 steps/s,
     * 0.5–3 m/s); each update moves the stride 10 % of the way to the measured value.
     */
    fun learnStride(gpsSpeedMps: Double, nowMs: Long) {
        val rate = cadence(nowMs) ?: return
        if (rate !in 1.0..3.0 || gpsSpeedMps !in 0.5..3.0) return
        val measured = (gpsSpeedMps / rate).coerceIn(MIN_STRIDE_M, MAX_STRIDE_M)
        strideM += LEARN_RATE * (measured - strideM)
    }

    private companion object {
        const val DEFAULT_STRIDE_M = 0.72
        const val MIN_STRIDE_M = 0.4
        const val MAX_STRIDE_M = 1.2
        const val LEARN_RATE = 0.1
        const val WINDOW_MS = 6_000L
        const val STOPPED_AFTER_MS = 2_500L
    }
}
