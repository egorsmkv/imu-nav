package org.blinddriver.core.imu

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
    val accMagnitude: Double get() = linearAcc?.let { magnitude(it) } ?: Double.NaN
    val gyroMagnitude: Double get() = gyro?.let { magnitude(it) } ?: Double.NaN

    private fun magnitude(v: FloatArray): Double = if (v.size < 3) Double.NaN else sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
}

/**
 * Estimates the gyroscope's vertical-axis bias while the car stands still: when GPS reports
 * < 0.5 m/s for at least 5 s, the mean yaw rate over that interval is blended into the bias.
 */
class GyroBiasEstimator {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var stillSinceMs = -1L

    /** Current bias estimate, deg/s. Subtract from raw yaw rates. */
    var biasDegS = 0.0
        private set

    fun addYawRate(elapsedMs: Long, yawRateDegS: Double) {
        samples.addLast(elapsedMs to yawRateDegS)
        while (samples.isNotEmpty() && elapsedMs - samples.first().first > 12_000) samples.removeFirst()
    }

    fun onGpsSpeed(elapsedMs: Long, speedMps: Float?) {
        if ((speedMps ?: 99f) >= 0.5f) {
            stillSinceMs = -1L
            return
        }
        if (stillSinceMs < 0) {
            stillSinceMs = elapsedMs
            return
        }
        if (elapsedMs - stillSinceMs < 5000) return
        val window = samples.filter { it.first >= stillSinceMs }
        if (window.size >= 20) {
            val mean = window.sumOf { it.second } / window.size
            if (abs(mean) < 3.0) biasDegS += 0.3 * (mean - biasDegS)
        }
        stillSinceMs = elapsedMs
    }
}
