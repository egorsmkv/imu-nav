package org.imunav.core.nav

/**
 * A motion-model hint derived entirely from recorded IMU and positioning inputs.
 * The factor is the existing stop/resume ramp, not a measured velocity. Reliable network
 * movement can veto a false stop. Expiry follows the IMU sample, not polling time.
 */
data class MotionEvidence(val factor: Double, val cruiseSpeedMps: Double, val validUntilMs: Long, val networkMoving: Boolean)
