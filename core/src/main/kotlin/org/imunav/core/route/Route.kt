package org.imunav.core.route

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
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

/** Things on the road that make cars slow down or stop. */
enum class HazardKind { TRAFFIC_SIGNAL, TRAFFIC_CALMING }

/** A hazard [s] meters from the start of the route. */
data class Hazard(val s: Double, val kind: HazardKind)

/**
 * A planned route.
 *
 * The road is a polyline: a list of points ([geometry]) joined by straight *segments*. Positions
 * along the route are given as **`s` = meters from the start, measured along the road** (the
 * "arc length"). The whole navigation engine works in `s`: "the car is at s = 1234 m" is
 * all it needs to know, because the car cannot leave the road sideways.
 *
 * [maxspeedKmh] and [segmentSpeedMps] have one entry per segment (`geometry.size - 1`) or are empty.
 * [elevationM] has one entry per point, or is null when the router gave no heights.
 */
class Route(
    val geometry: List<GeoPoint>,
    val steps: List<Step>,
    val durationS: Double,
    val maxspeedKmh: List<Int?> = emptyList(),
    val signals: List<GeoPoint> = emptyList(),
    val summary: String = "",
    /** Optional per-segment modeled travel speed from the router (m/s), used when no limit is known. */
    val segmentSpeedMps: List<Double?> = emptyList(),
    /**
     * Height above sea level of every [geometry] point, meters (offline packs built with elevation).
     * The engine compares it with the barometer to find where along the route the car is.
     */
    val elevationM: DoubleArray? = null,
) {
    init {
        require(elevationM == null || elevationM.size == geometry.size) { "elevationM needs one value per geometry point" }
    }

    /** Does this route know its height profile? */
    val hasElevation: Boolean get() = elevationM != null && geometry.size >= 2

    /** `cumulative[i]` = `s` of point `i`, i.e. the road distance from the start to that point. */
    val cumulative: DoubleArray = DoubleArray(geometry.size).also { cum ->
        for (i in 1 until geometry.size) cum[i] = cum[i - 1] + Geo.distance(geometry[i - 1], geometry[i])
    }

    /** Total route length, meters. */
    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Index of the segment that contains position [s] (binary search, fast even for long routes). */
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

    /** Posted speed limit on [segment] in km/h, or null if unknown. */
    fun maxspeedAtSegment(segment: Int): Int? = maxspeedKmh.getOrNull(segment)?.takeIf { it > 0 }

    /** The router's modeled travel speed on [segment] in m/s, or null if unknown. */
    fun modelledSpeedAtSegment(segment: Int): Double? = segmentSpeedMps.getOrNull(segment)?.takeIf { it > 0.5 }

    /** Position `s` of step [i]'s maneuver (where the driver turns). */
    fun stepS(i: Int): Double = cumulative[steps[i].geometryIndex.coerceIn(0, geometry.size - 1)]

    /** The map point and road direction at position [s]. */
    fun pointAt(s: Double): RoutePoint {
        if (geometry.size < 2) return RoutePoint(geometry.firstOrNull() ?: GeoPoint(0.0, 0.0), 0.0, 0)
        val segment = segmentAt(s)
        val start = geometry[segment]
        val end = geometry[segment + 1]
        val segmentLength = cumulative[segment + 1] - cumulative[segment]
        // How far along this segment we are: 0 = at its start, 1 = at its end.
        val fraction = if (segmentLength >= 1e-3) ((s - cumulative[segment]) / segmentLength).coerceIn(0.0, 1.0) else 0.0
        val point = GeoPoint(start.lat + (end.lat - start.lat) * fraction, Geo.normalizeLongitude(start.lon + Geo.longitudeDelta(start.lon, end.lon) * fraction))
        return RoutePoint(point, Geo.bearing(start, end), segment)
    }

    /** Height of the road at position [s] (linear between points), or null without elevation data. */
    fun elevationAt(s: Double): Double? {
        val heights = elevationM ?: return null
        if (geometry.size < 2) return heights.firstOrNull()
        val segment = segmentAt(s)
        val segmentLength = cumulative[segment + 1] - cumulative[segment]
        val fraction = if (segmentLength >= 1e-3) ((s - cumulative[segment]) / segmentLength).coerceIn(0.0, 1.0) else 0.0
        return heights[segment] + (heights[segment + 1] - heights[segment]) * fraction
    }

    /** Road direction (compass bearing) at position [s]. */
    fun bearingAt(s: Double): Double = pointAt(s.coerceIn(0.0, length)).bearingDeg

    /** Signed heading change of the road across [s] ± 25 m (deg, + = right). */
    fun turnAngleAt(s: Double): Double = Geo.angleDiff(bearingAt(maxOf(0.0, s - 25.0)), bearingAt(minOf(length, s + 25.0)))

    /**
     * Find the point of the route closest to [p] ("project" it onto the route).
     *
     * Searching the whole route every time would be slow and could jump to a far part of the
     * route that happens to pass nearby, so only [behindM] before and [aheadM] after [aroundS]
     * are searched. Only if that best match is farther than [globalIfFartherM] is the whole route searched.
     */
    fun project(p: GeoPoint, aroundS: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection {
        if (geometry.size < 2) {
            val only = geometry.firstOrNull() ?: p
            return Projection(0.0, Geo.distance(p, only), 0, only)
        }
        val startS = (aroundS - behindM).coerceIn(0.0, length)
        val endS = (aroundS + aheadM).coerceIn(0.0, length)
        val local = projectRange(p, segmentAt(startS), segmentAt(endS), startS, endS)
        // A window can cover every segment index while covering only part of those segments.
        if (local.offsetM > globalIfFartherM && (startS > 0.0 || endS < length)) {
            val global = projectRange(p, 0, geometry.size - 2)
            if (global.offsetM < local.offsetM) return global
        }
        return local
    }

    /** Closest point to [p] on segments [from]..[to], clipped to the arc-length window. */
    private fun projectRange(p: GeoPoint, from: Int, to: Int, startS: Double = 0.0, endS: Double = length): Projection {
        // Work in flat meters with p at the origin (0, 0): the math becomes simple 2-D vectors.
        val flat = LocalProjection(p)
        var best = Projection(0.0, Double.MAX_VALUE, from, geometry[from])
        for (i in from..minOf(to, geometry.size - 2)) {
            val startX = flat.segmentStartX(geometry[i], geometry[i + 1])
            val startY = flat.y(geometry[i])
            val dirX = flat.deltaX(geometry[i], geometry[i + 1])
            val dirY = flat.y(geometry[i + 1]) - startY
            val lengthSquared = dirX * dirX + dirY * dirY
            val segmentLength = cumulative[i + 1] - cumulative[i]
            val minFraction = if (segmentLength > 0.0) ((startS - cumulative[i]) / segmentLength).coerceIn(0.0, 1.0) else 0.0
            val maxFraction = if (segmentLength > 0.0) ((endS - cumulative[i]) / segmentLength).coerceIn(0.0, 1.0) else 0.0
            // Clamp to the permitted part of the segment, including a zero-width window.
            val fraction = if (lengthSquared < 1e-6) {
                minFraction
            } else {
                ((-startX * dirX - startY * dirY) / lengthSquared).coerceIn(minFraction, maxFraction)
            }
            val closestX = startX + dirX * fraction
            val closestY = startY + dirY * fraction
            val distance = sqrt(closestX * closestX + closestY * closestY)
            if (distance < best.offsetM) {
                val s = (cumulative[i] + fraction * segmentLength).coerceIn(startS, endS)
                best = Projection(s, distance, i, flat.toGeo(closestX, closestY))
            }
        }
        return best
    }

    /** Project traffic signals (and optional extra calming points) onto the route, sorted by s. */
    fun hazards(calming: List<GeoPoint> = emptyList(), maxOffsetM: Double = 30.0): List<Hazard> {
        fun toHazards(points: List<GeoPoint>, kind: HazardKind) = points.mapNotNull { point ->
            val projection = project(point, aroundS = 0.0, behindM = 0.0, aheadM = length, globalIfFartherM = 0.0)
            if (projection.offsetM <= maxOffsetM) Hazard(projection.s, kind) else null
        }
        return (toHazards(signals, HazardKind.TRAFFIC_SIGNAL) + toHazards(calming, HazardKind.TRAFFIC_CALMING)).sortedBy { it.s }
    }
}

/** Route-projection boundary; Android uses Rust while JVM replay uses this implementation. */
fun interface RouteProjector {
    fun project(route: Route, point: GeoPoint, aroundS: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection

    companion object {
        val KOTLIN = RouteProjector { route, point, aroundS, behindM, aheadM, globalIfFartherM ->
            route.project(point, aroundS, behindM, aheadM, globalIfFartherM)
        }
    }
}

/** A point on the route with the road's direction there. */
data class RoutePoint(val point: GeoPoint, val bearingDeg: Double, val segment: Int)

/**
 * Result of [Route.project]: the closest route position [s], how far the original point was from
 * the road ([offsetM], meters), and the closest [point] itself.
 */
data class Projection(val s: Double, val offsetM: Double, val segment: Int, val point: GeoPoint)

/**
 * The car's position on the route — the entire dead-reckoning state is this one number [s].
 * [moveTo] keeps it within the route.
 */
class RouteCursor(val route: Route) {
    var s: Double = 0.0
        private set

    fun moveTo(newS: Double) {
        s = newS.coerceIn(0.0, route.length)
    }

    fun advance(ds: Double) = moveTo(s + ds)

    val remaining: Double get() = route.length - s
}
