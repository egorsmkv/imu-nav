package org.imunav.core.nav

import org.imunav.core.imu.MotionDetector
import org.imunav.core.imu.Pedometer

/**
 * Export pedestrian speed, not car cruise/turn assumptions. Step windows may only predict until
 * the last step expires; repeated ticks cannot extend movement. Without steps, fresh IMU permits
 * an uncertain typical pace. Missing evidence leaves the native filter holding position.
 */
internal fun walkingEvidence(pedometer: Pedometer, motion: MotionDetector, nowMs: Long): MotionEvidence? {
    val speed = pedometer.speed(nowMs)
    if (speed != null) {
        val expiry = if (speed > 0.0) pedometer.movingUntilMs else nowMs + NavigationEngine.TICK_MS
        return MotionEvidence(1.0, speed, expiry, networkMoving = false, walking = true)
    }
    val factor = motion.motionFactor(nowMs) ?: return null
    return MotionEvidence(factor, TYPICAL_WALKING_SPEED_MPS, motion.validUntilMs, networkMoving = false, walking = true)
}

private const val TYPICAL_WALKING_SPEED_MPS = 1.3
