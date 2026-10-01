package org.imunav.core.nav

/**
 * A motion-model hint derived entirely from recorded inputs. Car hints use the stop/resume
 * ramp and a network veto; walking hints use cadence × learned stride or a fresh IMU pace
 * assumption. Neither is an independent position measurement.
 */
data class MotionEvidence(val factor: Double, val cruiseSpeedMps: Double, val validUntilMs: Long, val networkMoving: Boolean, val walking: Boolean = false)
