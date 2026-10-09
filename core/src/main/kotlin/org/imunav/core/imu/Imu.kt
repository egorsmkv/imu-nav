package org.imunav.core.imu

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One IMU sample, already rotated into the world frame by the platform adapter.
 *
 * @param yawRateDegS rotation rate about the world vertical, deg/s, positive = clockwise (right
 *   turn), i.e. the same sign convention as compass bearings. Independent of phone mounting.
 * @param linearAcc gravity-free acceleration vector (device frame is fine; only |a| is used)
 * @param gyro raw angular velocity vector, rad/s (only |ω| is used)
 */
class ImuSample(val elapsedMs: Long, val headingDeg: Float?, val yawRateDegS: Float?, val linearAcc: FloatArray?, val gyro: FloatArray?) {
    /** Length of the acceleration vector (m/s²), or NaN without accelerometer data. */
    val accMagnitude: Double get() = linearAcc?.let { magnitude(it) } ?: Double.NaN

    /** Length of the rotation vector (rad/s), or NaN without a gyroscope. */
    val gyroMagnitude: Double get() = gyro?.let { magnitude(it) } ?: Double.NaN

    private fun magnitude(v: FloatArray): Double = if (v.size < 3) Double.NaN else sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
}

/**
 * Estimates the gyroscope's error ("bias") around the vertical axis.
 *
 * A cheap gyroscope never reads exactly zero: standing still it may report e.g. 0.2 °/s, which
 * would add up to a fake 12° turn per minute. While GPS says the car stands still (below
 * 0.5 m/s for at least 5 s), everything the gyro measures must be bias, so we average it and
 * blend it slowly into [biasDegS].
 */
class GyroBiasEstimator {
    /** Recent (time, yaw rate) pairs, the last 12 s. */
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** When the current standstill started, -1 when moving. */
    private var stillSinceMs = -1L
    private var lastGpsMs = -1L

    /** Current bias estimate, deg/s. Subtract it from raw yaw rates. */
    var biasDegS = 0.0
        private set

    fun addYawRate(elapsedMs: Long, yawRateDegS: Double) {
        if (elapsedMs < 0 || !yawRateDegS.isFinite() || samples.lastOrNull()?.let { elapsedMs <= it.first } == true) return
        samples.addLast(elapsedMs to yawRateDegS)
        while (samples.isNotEmpty() && elapsedMs - samples.first().first > HISTORY_MS) samples.removeFirst()
    }

    fun onGpsSpeed(elapsedMs: Long, speedMps: Float?) {
        if (elapsedMs < 0 || elapsedMs <= lastGpsMs) return
        lastGpsMs = elapsedMs
        val standingStill = speedMps != null && speedMps in 0f..<STILL_SPEED_MPS
        if (!standingStill) {
            stillSinceMs = -1L
            return
        }
        if (stillSinceMs < 0) {
            stillSinceMs = elapsedMs
            return
        }
        if (elapsedMs - stillSinceMs < STILL_MIN_MS) return
        val window = samples.filter { (time, _) -> time in stillSinceMs..elapsedMs }
        if (window.size >= MIN_SAMPLES) {
            val mean = window.sumOf { (_, rate) -> rate } / window.size
            // A mean above 3 °/s is not bias (someone is turning the phone): ignore it.
            if (abs(mean) < MAX_BIAS_DEG_S) biasDegS += BLEND * (mean - biasDegS)
        }
        stillSinceMs = elapsedMs
    }

    private companion object {
        const val HISTORY_MS = 12_000L
        const val STILL_SPEED_MPS = 0.5f
        const val STILL_MIN_MS = 5000L
        const val MIN_SAMPLES = 20
        const val MAX_BIAS_DEG_S = 3.0

        /** Move 30 % of the way to the new measurement each time (smooths out noise). */
        const val BLEND = 0.3
    }
}
