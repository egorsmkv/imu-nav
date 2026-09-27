package org.blinddriver.core.route

import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.LocalProjection
import kotlin.math.sqrt

/**
 * One maneuver. [type] follows OSRM vocabulary: depart, turn, new name, merge, on ramp, off ramp,
 * fork, end of road, continue, roundabout, rotary, arrive...
 */
data class Step(
    val type: String,
    val modifier: String? = null,
    val name: String = "",
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    /** Index into [Route.geometry] of the maneuver location. */
    val geometryIndex: Int = 0,
    val roundaboutExit: Int? = null,
) {
    val isDepartOrArrive: Boolean get() = type == "depart" || type == "arrive"
}

enum class HazardKind { TRAFFIC_SIGNAL, TRAFFIC_CALMING }

/** A point hazard projected onto the route, at arc-length [s]. */
data class Hazard(val s: Double, val kind: HazardKind)

/**
 * A planned route: polyline, maneuvers, optional per-segment speed limits and traffic signals.
 * [maxspeedKmh] and [segmentSpeedMps] have one entry per segment (geometry.size - 1) or are empty.
 */
class Route(
    val geometry: List<GeoPoint>,
    val steps: List<Step>,
    val durationS: Double,
    val maxspeedKmh: List<Int?> = emptyList(),
    val signals: List<GeoPoint> = emptyList(),
    val summary: String = "",
    /** Optional per-segment modelled travel speed from the router (m/s), used when no limit is known. */
    val segmentSpeedMps: List<Double?> = emptyList(),
) {
    /** Cumulative distance at each vertex, metres. */
    val cumulative: DoubleArray = DoubleArray(geometry.size).also { cum ->
        for (i in 1 until geometry.size) cum[i] = cum[i - 1] + Geo.distance(geometry[i - 1], geometry[i])
    }

    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** Index of the segment containing arc-length [s] (binary search). */
    fun segmentAt(s: Double): Int {
        if (geometry.size < 2 || s <= 0.0) return 0
        if (s >= length) return geometry.size - 2
        var lo = 0
        var hi = geometry.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }

    fun maxspeedAtSegment(segment: Int): Int? = maxspeedKmh.getOrNull(segment)?.takeIf { it > 0 }

    fun modelledSpeedAtSegment(segment: Int): Double? = segmentSpeedMps.getOrNull(segment)?.takeIf { it > 0.5 }

    /** Arc-length of step [i]'s maneuver point. */
    fun stepS(i: Int): Double = cumulative[steps[i].geometryIndex.coerceIn(0, geometry.size - 1)]

    fun pointAt(s: Double): RoutePoint {
        if (geometry.size < 2) return RoutePoint(geometry.firstOrNull() ?: GeoPoint(0.0, 0.0), 0.0, 0)
        val seg = segmentAt(s)
        val a = geometry[seg]
        val b = geometry[seg + 1]
        val len = cumulative[seg + 1] - cumulative[seg]
        val f = if (len >= 1e-3) ((s - cumulative[seg]) / len).coerceIn(0.0, 1.0) else 0.0
        val p = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        return RoutePoint(p, Geo.bearing(a, b), seg)
    }

    fun bearingAt(s: Double): Double = pointAt(s.coerceIn(0.0, length)).bearingDeg

    /** Signed heading change of the road across [s] ± 25 m (deg, + = right). */
    fun turnAngleAt(s: Double): Double =
        Geo.angleDiff(bearingAt(maxOf(0.0, s - 25.0)), bearingAt(minOf(length, s + 25.0)))

    /**
     * Project [p] onto the route. Searches [behindM] before and [aheadM] after [aroundS]; if the
     * local match is farther than [globalIfFartherM], also searches the whole route.
     */
    fun project(p: GeoPoint, aroundS: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection {
        if (geometry.size < 2) {
            val only = geometry.firstOrNull() ?: p
            return Projection(0.0, Geo.distance(p, only), 0, only)
        }
        val local = projectRange(p, segmentAt(aroundS - behindM), segmentAt(aroundS + aheadM))
        if (local.offsetM > globalIfFartherM) {
            val global = projectRange(p, 0, geometry.size - 2)
            if (global.offsetM < local.offsetM) return global
        }
        return local
    }

    private fun projectRange(p: GeoPoint, from: Int, to: Int): Projection {
        val proj = LocalProjection(p)
        var best = Projection(0.0, Double.MAX_VALUE, from, geometry[from])
        for (i in from..minOf(to, geometry.size - 2)) {
            val ax = proj.x(geometry[i])
            val ay = proj.y(geometry[i])
            val dx = proj.x(geometry[i + 1]) - ax
            val dy = proj.y(geometry[i + 1]) - ay
            val len2 = dx * dx + dy * dy
            val f = if (len2 < 1e-6) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + dx * f
            val cy = ay + dy * f
            val d = sqrt(cx * cx + cy * cy)
            if (d < best.offsetM) {
                val s = cumulative[i] + f * (cumulative[i + 1] - cumulative[i])
                best = Projection(s, d, i, proj.toGeo(cx, cy))
            }
        }
        return best
    }

    /** Project traffic signals (and optional extra calming points) onto the route, sorted by s. */
    fun hazards(calming: List<GeoPoint> = emptyList(), maxOffsetM: Double = 30.0): List<Hazard> {
        fun toHazards(points: List<GeoPoint>, kind: HazardKind) = points.mapNotNull {
            val pr = project(it, 0.0, 0.0, length, 0.0)
            if (pr.offsetM <= maxOffsetM) Hazard(pr.s, kind) else null
        }
        return (toHazards(signals, HazardKind.TRAFFIC_SIGNAL) + toHazards(calming, HazardKind.TRAFFIC_CALMING)).sortedBy { it.s }
    }
}

data class RoutePoint(val point: GeoPoint, val bearingDeg: Double, val segment: Int)

data class Projection(val s: Double, val offsetM: Double, val segment: Int, val point: GeoPoint)

/** The whole dead-reckoning state: distance travelled along the route. */
class RouteCursor(val route: Route) {
    var s: Double = 0.0
        private set

    fun moveTo(newS: Double) {
        s = newS.coerceIn(0.0, route.length)
    }

    fun advance(ds: Double) = moveTo(s + ds)

    val remaining: Double get() = route.length - s
}
