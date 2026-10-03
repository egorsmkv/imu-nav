package org.imunav.app.car

import org.imunav.app.UiState
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode

/** Only values displayed by the host participate in publication, excluding logs and sensor diagnostics. */
data class CarTripContent(
    val step: Step?,
    val distances: CarDistances,
    val rerouting: Boolean,
    val arrived: Boolean,
    val destination: String?,
    val status: String,
    val clockMinute: Long,
)

/** Seconds match the precision accepted by the host's travel estimate API. */
data class CarDistances(val remainingM: Double, val remainingSeconds: Long, val nextM: Double)

/** Preview fields are independent of the moving position marker and its raw GPS diagnostics. */
data class CarPreviewContent(val endpoints: Pair<GeoPoint?, GeoPoint?>, val labels: Pair<String?, String?>, val route: Route?, val planning: Boolean, val mode: TravelMode)

/** Template-only fields include actions and the following maneuver, which are absent from host Trip. */
data class CarTemplateContent(val trip: CarTripContent, val preview: CarPreviewContent, val active: Boolean, val thenStep: Step?, val deviation: Boolean, val error: String?)

/** Refresh ETA at least once per wall-clock minute even when the vehicle is stationary. */
fun carTripContent(ui: UiState, status: String, wallMs: Long): CarTripContent = with(ui.guidance) {
    CarTripContent(nextStep, CarDistances(remainingM, remainingS.toLong(), distToNextM), rerouting, arrived, ui.destinationLabel, status, wallMs / 60_000)
}

/** Include preview actions and next-turn content even when host Trip remains unchanged. */
fun carTemplateContent(ui: UiState, trip: CarTripContent, mode: TravelMode): CarTemplateContent = CarTemplateContent(
    trip,
    CarPreviewContent(ui.destination to ui.manualStart, ui.destinationLabel to ui.manualStartLabel, ui.previewRoute, ui.planning, mode),
    ui.guidance.active,
    ui.guidance.thenStep,
    ui.guidance.blindDeviation,
    ui.error,
)
