package org.imunav.core.route

import org.imunav.core.geo.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * The area around a route that should be available offline (map tiles along the way).
 *
 * The corridor is a chain of overlapping squares centered on points every [spacingM] along the
 * route, each reaching [radiusM] in every direction. Squares are simple for the map library to
 * handle and cover curves well enough when the spacing is smaller than the radius.
 */
object RouteCorridor {
    /** A lat/lon box. */
    data class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    fun boxes(route: Route, radiusM: Double = 1_000.0, spacingM: Double = 800.0): List<Box> {
        if (route.geometry.isEmpty()) return emptyList()
        val centres = buildList {
            var s = 0.0
            while (s < route.length) {
                add(route.pointAt(s).point)
                s += spacingM
            }
            add(route.geometry.last())
        }
        return centres.map { p ->
            val dLat = radiusM / Geo.M_PER_DEG_LAT
            val dLon = radiusM / (Geo.M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(p.lat)))
            Box(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)
        }
    }

    /**
     * How many map tiles (Web Mercator z/x/y) the boxes touch from [minZoom] to [maxZoom] —
     * counted exactly, so a download can be sized before it starts.
     */
    fun tileCount(boxes: List<Box>, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val tiles = HashSet<Long>()
            for (box in boxes) {
                val x0 = tileX(box.minLon, z)
                val x1 = tileX(box.maxLon, z)
                val y0 = tileY(box.maxLat, z) // tile rows grow southward
                val y1 = tileY(box.minLat, z)
                for (x in x0..x1) for (y in y0..y1) tiles += x.toLong() shl 32 or y.toLong()
            }
            total += tiles.size
        }
        return total
    }

    private fun tileX(lon: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom)
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
    }

    private fun tileY(lat: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n.toInt() - 1)
    }

    /** Bounding box of all [boxes]. */
    fun bounds(boxes: List<Box>): Box = Box(boxes.minOf { it.minLat }, boxes.minOf { it.minLon }, boxes.maxOf { it.maxLat }, boxes.maxOf { it.maxLon })
}
