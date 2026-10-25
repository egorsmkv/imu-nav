package org.imunav.core.nav

/** Persistent map choices; hiding controls never changes positioning or tower collection. */
data class MapDisplayOptions(
    val buildings3d: Boolean = true,
    val carPerspective: Boolean = true,
    /** Map tilt in the driving perspective, degrees ([NavigationMapCamera.MIN_CAR_TILT_DEG]..[NavigationMapCamera.MAX_CAR_TILT_DEG]). */
    val carTiltDeg: Double = NavigationMapCamera.CAR_TILT_DEG,
    /** Draw the own position as a flat arrow turned to the heading (instead of a dot with a beam). */
    val directionArrow: Boolean = true,
    val automaticZoom: Boolean = true,
    val hideTowerButton: Boolean = false,
    val hideZoomButtons: Boolean = false,
    /** Users who navigate by cell towers and OBD-II on purpose keep Android Location off; the banner only gets in their way. */
    val hideLocationOffWarning: Boolean = false,
) {
    fun showTowerButton(navigating: Boolean): Boolean = !navigating || !hideTowerButton
    fun showZoomButtons(navigating: Boolean): Boolean = !navigating || !hideZoomButtons
}
