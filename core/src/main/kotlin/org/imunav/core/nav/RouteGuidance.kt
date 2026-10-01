package org.imunav.core.nav

import org.imunav.core.route.RouteCursor
import kotlin.math.max
import kotlin.math.min

/** Derive all route-relative UI fields from one position, regardless of which estimator owns it. */
internal fun GuidanceState.withRouteProgress(car: RouteCursor, arriveM: Double, ready: Boolean): GuidanceState {
    val route = car.route
    val point = route.pointAt(car.s)
    val next = route.steps.indices.firstOrNull { route.steps[it].type != "depart" && route.stepS(it) > car.s + 8.0 } ?: -1
    val remaining = route.length - car.s
    return copy(
        active = true,
        route = route,
        s = car.s,
        position = point.point,
        bearingDeg = point.bearingDeg.toFloat(),
        nextStep = route.steps.getOrNull(next),
        nextStepIndex = next,
        distToNextM = if (next >= 0) route.stepS(next) - car.s else 0.0,
        thenStep = if (next >= 0) route.steps.getOrNull(next + 1) else null,
        remainingM = remaining,
        remainingS = if (route.length > 0) route.durationS * remaining / route.length else 0.0,
        speedLimitKmh = route.maxspeedAtSegment(point.segment),
        arrived = ready && remaining < arriveM,
    )
}

/** Legacy Kotlin drift display; native mode supplies its covariance-plus-systematic safety radius instead. */
internal fun kotlinUncertainty(source: PositionSource, cellAccuracyM: Double, gpsUsed: Boolean, startAccuracyM: Double, netFresh: Boolean, driftM: Double): Double = when {
    source.isGps -> 15.0

    source == PositionSource.CELL -> cellAccuracyM

    else -> {
        val anchor = if (gpsUsed) 25.0 else max(25.0, startAccuracyM)
        val cap = max(if (netFresh) 350.0 else 600.0, anchor)
        max(30.0, min(cap, anchor + driftM))
    }
}
