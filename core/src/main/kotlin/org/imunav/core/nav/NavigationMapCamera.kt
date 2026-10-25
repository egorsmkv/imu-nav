package org.imunav.core.nav

import org.imunav.core.route.TravelMode

/** Camera policy uses the navigation engine's speed, never a separate or untrusted GPS feed. */
object NavigationMapCamera {
    const val CAR_ZOOM = 16.0
    const val WALK_ZOOM = 17.5
    const val CAR_TILT_DEG = 45.0

    /** Settings range for the driving tilt; MapLibre's default maximum pitch is 60°. */
    const val MIN_CAR_TILT_DEG = 30.0
    const val MAX_CAR_TILT_DEG = 60.0
    private const val CLOSE_ZOOM = 17.0
    private const val WIDE_ZOOM = 14.5
    private const val CLOSE_SPEED_KMH = 10.0
    private const val WIDE_SPEED_KMH = 130.0

    /** Continuous, bounded zoom avoids sudden jumps at speed-band boundaries. */
    fun zoom(mode: TravelMode, speedKmh: Float, automatic: Boolean): Double {
        if (mode == TravelMode.FOOT) return WALK_ZOOM
        if (!automatic) return CAR_ZOOM
        val speed = if (speedKmh.isFinite()) speedKmh.toDouble() else 0.0
        val fraction = ((speed - CLOSE_SPEED_KMH) / (WIDE_SPEED_KMH - CLOSE_SPEED_KMH)).coerceIn(0.0, 1.0)
        return CLOSE_ZOOM + (WIDE_ZOOM - CLOSE_ZOOM) * fraction
    }

    /**
     * Walking and route browsing always return to a flat map. A larger [carTiltDeg] shows more
     * road ahead; invalid or out-of-range values are clamped to the supported range.
     */
    fun tilt(mode: TravelMode, navigating: Boolean, perspective: Boolean, carTiltDeg: Double = CAR_TILT_DEG): Double = if (navigating && mode == TravelMode.CAR && perspective) {
        clampTilt(carTiltDeg)
    } else {
        0.0
    }

    /** Keep a stored or synced tilt within what the map supports. */
    fun clampTilt(degrees: Double): Double = if (degrees.isFinite()) degrees.coerceIn(MIN_CAR_TILT_DEG, MAX_CAR_TILT_DEG) else CAR_TILT_DEG
}
