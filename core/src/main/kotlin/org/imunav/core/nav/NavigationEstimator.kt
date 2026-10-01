package org.imunav.core.nav

import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.imu.TurnEvidence

/** Position owner selected before a trip; each backend uses the selected travel mode's motion model. */
enum class NavigationEstimator {
    KOTLIN,
    NATIVE_KALMAN,
}

/** A route-state estimate, including whether this tick accepted the supplied GPS position. */
data class RouteEstimate(val positionM: Double, val speedMps: Double, val safetyRadiusM: Double, val gpsPositionAccepted: Boolean, val gpsSpeedAccepted: Boolean = false) {
    val valid: Boolean
        get() = positionM.isFinite() && speedMps.isFinite() && speedMps >= 0.0 && safetyRadiusM.isFinite() && safetyRadiusM >= 0.0
}

/** Android supplies JNI; tests can supply deterministic estimates without loading native code. */
fun interface RouteEstimateProvider {
    /** Null means unavailable (for example while restoring route geometry), never permission to dead reckon twice. */
    fun estimate(nowMs: Long, positioning: PositioningSnapshot, motion: MotionEvidence?, turn: TurnEvidence?): RouteEstimate?
}
