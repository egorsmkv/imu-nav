package org.imunav.core.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A position on Earth in degrees (WGS 84, the system GPS uses). North and east are positive. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Small geometry helpers. Angles are in degrees, distances in metres. */
object Geo {
    private const val EARTH_DIAMETER_M = 12_742_000.0
    const val M_PER_DEG_LAT = 110_540.0
    const val M_PER_DEG_LON_EQUATOR = 111_320.0

    /**
     * Distance between two points along the Earth's surface, in metres.
     *
     * Uses the haversine formula, which treats the Earth as a sphere. That is accurate to about
     * 0.5 %, far better than any GPS fix, and stays numerically stable for very short distances.
     */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val halfDeltaLat = Math.toRadians(lat2 - lat1) / 2
        val halfDeltaLon = Math.toRadians(lon2 - lon1) / 2
        val haversine = sin(halfDeltaLat) * sin(halfDeltaLat) +
            sin(halfDeltaLon) * sin(halfDeltaLon) * cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2))
        return atan2(sqrt(haversine), sqrt(1 - haversine)) * EARTH_DIAMETER_M
    }

    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Bearing a -> b in degrees [0, 360), using a local flat-earth approximation (fine for road segments). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dx = (b.lon - a.lon) * M_PER_DEG_LON_EQUATOR * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = (b.lat - a.lat) * M_PER_DEG_LAT
        return normalize(Math.toDegrees(atan2(dx, dy)))
    }

    /** Any angle → the same direction in [0, 360). Example: -90 → 270, 370 → 10. */
    fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /**
     * The smallest rotation that turns heading [from] into heading [to], in (-180, 180].
     * Positive = clockwise (a right turn). Example: from 350° to 10° is +20°, not -340°.
     */
    fun angleDiff(from: Double, to: Double): Double {
        var diff = (to - from) % 360.0
        if (diff > 180.0) diff -= 360.0
        if (diff <= -180.0) diff += 360.0
        return diff
    }

    /** How far apart two headings are, ignoring direction: always 0…180°. */
    fun absAngleDiff(a: Double, b: Double): Double = abs(angleDiff(a, b))
}

/**
 * Converts lat/lon to flat x/y metres around [origin] (x = east, y = north), so that nearby
 * geometry can use plain 2-D maths. Accurate to well under 1 % within tens of kilometres.
 *
 * (A degree of longitude gets shorter towards the poles, hence the `cos(latitude)` factor.)
 */
class LocalProjection(val origin: GeoPoint) {
    private val mPerDegLon = Geo.M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(origin.lat))

    fun x(p: GeoPoint): Double = (p.lon - origin.lon) * mPerDegLon
    fun y(p: GeoPoint): Double = (p.lat - origin.lat) * Geo.M_PER_DEG_LAT

    fun toGeo(x: Double, y: Double): GeoPoint = GeoPoint(origin.lat + y / Geo.M_PER_DEG_LAT, origin.lon + x / mPerDegLon)
}

/**
 * A polygon on the map (a closed ring of vertices). Used for the service-area border.
 * The bounding box makes the common "far away" case a quick reject.
 */
class Polygon(private val lons: DoubleArray, private val lats: DoubleArray) {
    private val minLon = lons.min()
    private val maxLon = lons.max()
    private val minLat = lats.min()
    private val maxLat = lats.max()

    init {
        require(lons.size == lats.size && lons.size >= 3) { "a polygon needs at least 3 vertices" }
    }

    /**
     * Point-in-polygon test by "ray casting": walk east from the point and count how many edges
     * we cross. An odd count means the point is inside.
     */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) return false
        var inside = false
        var j = lons.size - 1
        for (i in lons.indices) {
            if ((lats[i] > lat) != (lats[j] > lat) &&
                lon < (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i]) + lons[i]
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        /** Build from an interleaved [lon0, lat0, lon1, lat1, ...] array. */
        fun fromLonLat(coords: DoubleArray): Polygon {
            val n = coords.size / 2
            return Polygon(DoubleArray(n) { coords[2 * it] }, DoubleArray(n) { coords[2 * it + 1] })
        }
    }
}

/** A service area made of one or more polygons. Fixes outside it are treated as spoofed. */
fun interface ServiceArea {
    fun contains(lat: Double, lon: Double): Boolean

    companion object {
        val EVERYWHERE = ServiceArea { _, _ -> true }

        fun of(polygons: List<Polygon>) = ServiceArea { lat, lon -> polygons.any { it.contains(lat, lon) } }

        /**
         * Coarse outline of Ukraine (~50 vertices, lon/lat, within a few km of the real border).
         * Good enough to reject spoofed fixes that teleport the phone to another country;
         * supply a precise border polygon for anything stricter.
         */
        val UKRAINE_COARSE: ServiceArea = of(
            listOf(
                Polygon.fromLonLat(
                    doubleArrayOf(
                        23.6, 51.6, 24.4, 51.9, 26.0, 51.92, 27.7, 51.6, 29.2, 51.6, 30.55, 51.3,
                        30.95, 52.05, 32.3, 52.1, 33.5, 52.37, 34.4, 51.25, 35.4, 50.45, 36.3, 50.3,
                        37.6, 50.35, 38.3, 49.95, 40.1, 49.6, 40.2, 48.9, 39.8, 48.3, 39.95, 47.85,
                        38.8, 47.2, 38.3, 47.1, 37.55, 46.85, 36.8, 46.6, 35.1, 46.25, 35.1, 45.3,
                        36.65, 45.45, 36.5, 45.05, 35.4, 44.95, 34.4, 44.35, 33.4, 44.45, 32.4, 45.35,
                        33.6, 46.1, 32.1, 46.2, 31.2, 46.5, 30.6, 46.2, 30.2, 45.85, 29.6, 45.25,
                        28.2, 45.45, 28.9, 46.3, 29.9, 46.55, 29.2, 47.1, 29.6, 47.9, 27.6, 48.45,
                        26.6, 48.3, 25.2, 47.75, 24.1, 47.9, 22.9, 48.05, 22.15, 48.4, 22.6, 49.1,
                        23.6, 50.4, 24.05, 50.85,
                    ),
                ),
            ),
        )
    }
}
