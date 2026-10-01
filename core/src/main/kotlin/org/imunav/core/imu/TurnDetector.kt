package org.imunav.core.imu

import kotlin.math.PI
import kotlin.math.abs

/** A completed rotation from recorded IMU samples, not a route match or position correction. */
data class TurnEvidence(val startMs: Long, val endMs: Long, val angleDeg: Double)

/**
 * Extracts isolated car-like yaw rotations for the native comparison estimator. It deliberately
 * ignores short swings, heavy tilt, reversals and incomplete data. Smooth phone yaw can still mimic
 * a car turn; route matching must remain conservative. No live navigation decisions feed this class.
 */
class TurnDetector {
    private class Segment(val startMs: Long) {
        var endMs = startMs
        var angle = 0.0
        var absoluteAngle = 0.0
    }

    private var lastMs = -1L
    private var lastYaw = 0.0
    private var quietSince = -1L
    private var armedUntilMs = -1L
    private var segment: Segment? = null
    private var completed: TurnEvidence? = null

    /** Route changes and trips must not reuse a rotation begun on a previous route. */
    fun reset() {
        lastMs = -1
        lastYaw = 0.0
        quietSince = -1
        armedUntilMs = -1
        segment = null
        completed = null
    }

    /** Uses sample timestamps, not navigation ticks, so gaps cannot invent integrated rotation. */
    fun add(sample: ImuSample, yawBiasDegS: Double = 0.0) {
        val now = sample.elapsedMs
        if (now <= lastMs) return
        val yaw = (sample.yawRateDegS?.toDouble() ?: Double.NaN) - yawBiasDegS
        val gyroDegS = sample.gyroMagnitude * DEGREES_PER_RADIAN
        val acceleration = sample.accMagnitude
        val valid = yaw.isFinite() && gyroDegS.isFinite() && acceleration.isFinite() &&
            abs(yaw) <= MAX_YAW_DEG_S && gyroDegS <= abs(yaw) + MAX_EXTRA_ROTATION_DEG_S && acceleration <= MAX_ACCELERATION
        if (!valid || lastMs < 0 || now - lastMs > MAX_GAP_MS) {
            reset()
            lastMs = now
            lastYaw = if (valid) yaw else 0.0
            return
        }
        val active = segment
        if (active != null) {
            integrate(active, now, yaw)
        } else if (abs(yaw) < QUIET_YAW_DEG_S) {
            if (quietSince < 0) quietSince = now
            if (now - quietSince >= PRE_STRAIGHT_MS) armedUntilMs = now + START_GRACE_MS
        } else {
            if (now <= armedUntilMs && abs(yaw) >= START_YAW_DEG_S) {
                segment = Segment(lastMs).also { integrate(it, now, yaw) }
                completed = null
            }
            quietSince = -1
        }
        lastMs = now
        lastYaw = yaw
    }

    private fun integrate(active: Segment, now: Long, yaw: Double) {
        val rotation = (lastYaw + yaw) * 0.5 * (now - lastMs) / 1000.0
        active.angle += rotation
        active.absoluteAngle += abs(rotation)
        if (abs(yaw) >= QUIET_YAW_DEG_S) active.endMs = now
        if (now - active.startMs > MAX_TURN_MS + POST_STRAIGHT_MS) {
            segment = null
            quietSince = -1
            return
        }
        if (now - active.endMs < POST_STRAIGHT_MS) return
        val duration = active.endMs - active.startMs
        if (duration in MIN_TURN_MS..MAX_TURN_MS && abs(active.angle) in MIN_ANGLE_DEG..MAX_ANGLE_DEG &&
            abs(active.angle) >= DIRECTION_PURITY * active.absoluteAngle
        ) {
            completed = TurnEvidence(active.startMs, active.endMs, active.angle)
        }
        segment = null
        quietSince = active.endMs
    }

    /** A brief publication window lets a normal 500 ms tick see the event without refreshing it. */
    fun evidence(nowMs: Long): TurnEvidence? = completed?.takeIf {
        nowMs - lastMs in 0..MAX_GAP_MS && nowMs - it.endMs in 0..EVIDENCE_MAX_AGE_MS
    }

    private companion object {
        const val DEGREES_PER_RADIAN = 180.0 / PI
        const val MAX_GAP_MS = 200L
        const val PRE_STRAIGHT_MS = 1000L
        const val START_GRACE_MS = 500L
        const val POST_STRAIGHT_MS = 700L
        const val EVIDENCE_MAX_AGE_MS = 2000L
        const val MIN_TURN_MS = 1500L
        const val MAX_TURN_MS = 8000L
        const val QUIET_YAW_DEG_S = 3.0
        const val START_YAW_DEG_S = 8.0
        const val MAX_YAW_DEG_S = 55.0
        const val MAX_EXTRA_ROTATION_DEG_S = 15.0
        const val MAX_ACCELERATION = 6.0
        const val MIN_ANGLE_DEG = 45.0
        const val MAX_ANGLE_DEG = 125.0
        const val DIRECTION_PURITY = 0.9
    }
}
