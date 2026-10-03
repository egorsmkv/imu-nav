package org.imunav.app.car

import org.imunav.app.UiState
import org.imunav.core.geo.GeoPoint

/** Map source and camera inputs, independent of logs, guidance text and other template state. */
data class CarMapContent(val destination: GeoPoint?, val position: Pair<GeoPoint?, Double?>, val camera: Pair<GeoPoint, Double>?, val maximumFps: Int)

/** Each native property is updated only when its own input changes. */
data class CarMapChanges(val content: CarMapContent, val destination: Boolean, val position: Boolean, val camera: Boolean, val maximumFps: Boolean)

/** A hidden surface must not consume updates: its next visible frame compares against what was drawn. */
class CarMapUpdates {
    private var applied: CarMapContent? = null

    /** Record only frames the renderer can apply, leaving hidden changes pending in its UI snapshot. */
    fun update(content: CarMapContent, visible: Boolean): CarMapChanges? {
        if (!visible) return null
        val previous = applied
        applied = content
        return CarMapChanges(
            content,
            previous == null || previous.destination != content.destination,
            previous?.position != content.position,
            previous?.camera != content.camera,
            previous?.maximumFps != content.maximumFps,
        )
    }

    /** A new style or surface has no previously drawn markers. */
    fun reset() {
        applied = null
    }

    /** User pan/recenter can move the camera without changing navigation state. */
    fun invalidateCamera() {
        applied = applied?.copy(camera = null)
    }
}

/** Keep the idle/manual camera behavior identical to the phone's route review. */
fun carMapContent(ui: UiState, following: Boolean, maximumFps: Int): CarMapContent {
    val route = ui.guidance.route ?: ui.previewRoute
    val point = ui.currentPosition ?: ui.manualStart ?: ui.destination
    val camera = point?.takeIf { following && (ui.guidance.active || route == null) }
        ?.let { it to if (ui.guidance.active) ui.guidance.bearingDeg.toDouble() else 0.0 }
    return CarMapContent(
        ui.destination ?: ui.guidance.destination,
        ui.currentPosition to if (ui.guidance.active) ui.guidance.uncertaintyM else ui.trustedAccuracyM,
        camera,
        maximumFps,
    )
}
