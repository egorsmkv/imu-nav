package org.blinddriver.core.imu

import org.blinddriver.core.Tuning
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decides from vibration alone whether the car is standing or moving, and integrates the
 * vertical yaw rate for turn detection.
 *
 * Stopped: over the last `stopWindowMs` the linear acceleration is quiet (low mean AND low
 * spread), or the gyro is quiet while acceleration is only mildly noisy; held for `stopHoldMs`.
 * Moving: any of mean / spread / gyro exceeds the resume thresholds for `resumeConfirmMs`.
 *
 * After a resume, [motionFactor] ramps 0.15 → 1 over `resumeRampMs` (capped at
 * `resumeSlowCap` during the first `resumeSlowMs`) to model the car accelerating from rest.
 */
class MotionDetector(private val tuning: () -> Tuning) {
    private class Sample(val t: Long, val acc: Double, val yaw: Double, val gyro: Double)

    private val samples = ArrayDeque<Sample>()
    private var quietSinceMs = -1L
    private var noisySinceMs = -1L
    private var resumedAtMs = -1L
    private var lastSampleMs = 0L

    /** Yaw integration for [integratedYaw] only counts samples after this time. */
    var turnResetMs = -1L

    /** Separate accumulator used while the marker is held before a turn. */
    var holdYawDeg = 0.0
        private set
    private var holdStartedMs = -1L
    val holdAccumulating: Boolean get() = holdStartedMs >= 0

    var stopped = false
        private set
    var accStd = 0.0
        private set
    var accMean = 0.0
        private set
    var gyroMean = Double.NaN
        private set

    /** Optional sink for human-readable events. */
    var log: ((String) -> Unit)? = null

    fun add(sample: ImuSample, yawBiasDegS: Double = 0.0) {
        val acc = sample.accMagnitude
        if (acc.isNaN()) return
        val t = tuning()
        val now = sample.elapsedMs
        lastSampleMs = now
        val yaw = (sample.yawRateDegS?.toDouble() ?: 0.0) - yawBiasDegS
        samples.addLast(Sample(now, acc, yaw, sample.gyroMagnitude))
        while (samples.isNotEmpty() && now - samples.first().t > 8000) samples.removeFirst()

        if (holdStartedMs in 0 until now) {
            holdYawDeg += (now - holdStartedMs) / 1000.0 * yaw
            holdStartedMs = now
        }

        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var gyroN = 0
        var gyroSum = 0.0
        for (s in samples) {
            if (now - s.t > t.stopWindowMs) continue
            n++
            sum += s.acc
            sumSq += s.acc * s.acc
            if (!s.gyro.isNaN()) {
                gyroN++
                gyroSum += s.gyro
            }
        }
        if (n < 8) return
        val mean = sum / n
        val std = sqrt(max(0.0, sumSq / n - mean * mean))
        val gyro = if (gyroN * 5 >= n * 4) gyroSum / gyroN else Double.NaN
        accMean = mean
        accStd = std
        gyroMean = gyro

        if (!stopped) {
            val quietAcc = std < t.stopAccStd && mean < t.stopAccMean
            val quietGyro = !gyro.isNaN() && gyro < t.stopGyro && mean < t.stopGyroAccMeanMax
            if (!quietAcc && !quietGyro) {
                quietSinceMs = -1L
                return
            }
            if (quietSinceMs < 0) quietSinceMs = now
            if (now - quietSinceMs >= t.stopHoldMs) {
                stopped = true
                noisySinceMs = -1L
                log?.invoke("dr_stop std=%.3f mean=%.3f gyro=%.3f".format(std, mean, if (gyro.isNaN()) -1.0 else gyro))
            }
            return
        }

        val stillQuiet = mean <= t.resumeAccMean && std <= t.resumeAccStd && (gyro.isNaN() || gyro <= t.resumeGyro)
        if (stillQuiet) {
            noisySinceMs = -1L
            return
        }
        if (noisySinceMs < 0) noisySinceMs = now
        if (now - noisySinceMs >= t.resumeConfirmMs) {
            stopped = false
            quietSinceMs = -1L
            resumedAtMs = now
            log?.invoke("dr_resume std=%.3f mean=%.3f gyro=%.3f".format(std, mean, if (gyro.isNaN()) -1.0 else gyro))
        }
    }

    /**
     * Fraction of the modelled cruising speed the car is doing right now:
     * null = no fresh IMU data, 0 = stopped, ramp after a resume, otherwise 1.
     */
    fun motionFactor(nowMs: Long): Double? {
        if (lastSampleMs <= 0 || nowMs - lastSampleMs >= 2000) return null
        if (stopped) return 0.0
        if (resumedAtMs > 0) {
            val t = tuning()
            val since = nowMs - resumedAtMs
            if (since < t.resumeRampMs) {
                val ramp = (since.toDouble() / t.resumeRampMs).coerceIn(0.15, 1.0)
                return if (since < t.resumeSlowMs) min(ramp, t.resumeSlowCap) else ramp
            }
        }
        return 1.0
    }

    /** Heading change (deg, + = right) over the last [windowMs], excluding samples before [turnResetMs]. */
    fun integratedYaw(nowMs: Long, windowMs: Long): Double {
        var total = 0.0
        var prev: Sample? = null
        for (s in samples) {
            if (nowMs - s.t <= windowMs && s.t > turnResetMs && prev != null) {
                total += (s.t - prev.t) / 1000.0 * s.yaw
            }
            prev = s
        }
        return total
    }

    fun startHoldAccumulator(nowMs: Long) {
        holdYawDeg = 0.0
        holdStartedMs = nowMs
    }

    fun resetHoldAccumulator() {
        holdYawDeg = 0.0
        holdStartedMs = -1L
    }
}
