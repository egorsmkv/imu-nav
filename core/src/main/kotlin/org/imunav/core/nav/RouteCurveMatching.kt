package org.imunav.core.nav

import org.imunav.core.geo.Geo
import org.imunav.core.route.RouteCursor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Largest same-direction heading change ≥ 70% of [yaw] within any 400 m of road around the marker. */
internal fun routeCurveMatching(car: RouteCursor, yaw: Double): Double? {
    val route = car.route
    val from = max(car.s - 400.0, 0.0)
    val to = min(car.s + 300.0, route.length)
    if (to - from < 20.0) return null
    // Total heading change of the road from `from` to every 10 m step after it.
    val headingChange = ArrayList<Double>()
    var previousBearing = route.bearingAt(from)
    var total = 0.0
    headingChange += 0.0
    var probeS = from + 10.0
    while (probeS <= to) {
        val bearing = route.bearingAt(probeS)
        total += Geo.angleDiff(previousBearing, bearing)
        headingChange += total
        previousBearing = bearing
        probeS += 10.0
    }
    // Heading change over every stretch of up to 40 × 10 m = 400 m.
    val needed = abs(yaw) * 0.7
    var best: Double? = null
    for (i in headingChange.indices) {
        for (j in i + 1..min(headingChange.size - 1, i + 40)) {
            val change = headingChange[j] - headingChange[i]
            if (change * yaw > 0 && abs(change) >= needed && (best == null || abs(change) > abs(best))) best = change
        }
    }
    return best
}
