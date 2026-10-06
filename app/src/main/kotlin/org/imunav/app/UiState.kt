package org.imunav.app

import org.imunav.app.cells.CellStatus
import org.imunav.app.sensors.NavigationSensor
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.GnssSnapshot
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.Verdict
import org.imunav.core.nav.GuidanceState
import org.imunav.core.route.Route

/**
 * Everything the UI shows, as one immutable value. [AppGraph] publishes a new copy (via a
 * StateFlow) every engine tick; Compose redraws only the parts that changed.
 */
data class UiState(
    /** Navigation state from the engine (route, next maneuver, position on the route…). */
    val guidance: GuidanceState = GuidanceState(),
    val gpsState: GpsState = GpsState.LOST,
    /** The classifier's verdict on the latest GPS fix (for the diagnostics sheet). */
    val lastVerdict: Verdict? = null,
    val gnss: GnssSnapshot = GnssSnapshot(),
    val jammed: Boolean = false,
    /** Where to draw the position dot (active engine estimate, else manual start or fresh automatic fix). */
    val currentPosition: GeoPoint? = null,
    /** Destination picked on the map or in search, before navigation starts. */
    val destination: GeoPoint? = null,
    /** Address shown for a destination chosen in search; null for a point chosen directly on the map. */
    val destinationLabel: String? = null,
    /** One-shot camera request when a bookmark is applied from the library. */
    val mapFocusRequest: GeoPoint? = null,
    /** Start point chosen by the user on the map or in search; it overrides the trusted position. */
    val manualStart: GeoPoint? = null,
    /** Address shown for a start chosen in search; null for a point chosen directly on the map. */
    val manualStartLabel: String? = null,
    val hasTrustedPosition: Boolean = false,
    /** Accuracy of the best trusted position, metres (null = none). */
    val trustedAccuracyM: Double? = null,
    /** The trusted position comes from a GOOD GPS fix (else from cell/network). */
    val trustedFromGps: Boolean = false,
    /** Why GPS fixes are currently rejected, for the "no trusted position" message. */
    val gpsRejectReasons: List<String> = emptyList(),
    /** A route is being computed. */
    val planning: Boolean = false,
    /** A start transaction owns the editor until navigation is installed or fails. */
    val startingNavigation: Boolean = false,
    /** Route shown before navigation starts, so the user can review the proposed way. */
    val previewRoute: Route? = null,
    /** A message to show once in a snackbar (then cleared with [AppGraph.clearError]). */
    val error: String? = null,
    /** Debug switch: pretend GPS is jammed. */
    val simulateGpsLoss: Boolean = false,
    /** The phone lacks some sensors (e.g. no gyroscope); shown in diagnostics. */
    val missingSensors: List<NavigationSensor> = emptyList(),
    /** System-wide Location switch; when off, Android delivers no fixes to any app. */
    val locationEnabled: Boolean = true,
    /** The latest trip-log lines. */
    val log: List<String> = emptyList(),
    val cells: CellStatus = CellStatus(),
)
