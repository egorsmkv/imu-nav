package org.imunav.core.nav

/** Persistent map choices; hiding controls never changes positioning or tower collection. */
data class MapDisplayOptions(
    val buildings3d: Boolean = true,
    val carPerspective: Boolean = true,
    val automaticZoom: Boolean = true,
    val hideTowerButton: Boolean = false,
    val hideZoomButtons: Boolean = false,
) {
    fun showTowerButton(navigating: Boolean): Boolean = !navigating || !hideTowerButton
    fun showZoomButtons(navigating: Boolean): Boolean = !navigating || !hideZoomButtons
}
