package org.blinddriver.core

import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.LocalProjection
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.RouteCorridor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteCorridorTest {
    private val proj = LocalProjection(GeoPoint(50.45, 30.52))

    @Test
    fun boxesCoverTheWholeRouteWithMargin() {
        val route = Route((0..10_000 step 100).map { proj.toGeo(0.0, it.toDouble()) }, emptyList(), 600.0) // 10 km north
        val boxes = RouteCorridor.boxes(route, radiusM = 1_000.0, spacingM = 800.0)
        assertEquals(14, boxes.size, "a box every 800 m plus one at the end")
        // Every route point lies inside some box, at least 900 m from its edge sideways.
        for (p in route.geometry) {
            assertTrue(boxes.any { p.lat in it.minLat..it.maxLat && p.lon in it.minLon..it.maxLon })
        }
        val first = boxes.first()
        val halfWidthM = Geo.distance(first.minLat, first.minLon, first.minLat, (first.minLon + first.maxLon) / 2)
        assertEquals(1_000.0, halfWidthM, 20.0)
    }

    @Test
    fun tileCountIsSmallEnoughForALongDrive() {
        // ~480 km, like Kyiv → Odesa.
        val route = Route((0..480).map { GeoPoint(50.45 - it * 0.008, 30.52 + it * 0.0004) }, emptyList(), 18_000.0)
        val boxes = RouteCorridor.boxes(route)
        val tiles = RouteCorridor.tileCount(boxes, minZoom = 10, maxZoom = 14)
        println("Kyiv→Odesa corridor: ${boxes.size} boxes, $tiles tiles z10–14")
        assertTrue(tiles in 500..6_000, "tiles $tiles")
        // One zoom level up quadruples the tiles for area coverage; here the corridor is narrow, so it roughly doubles.
        val z14 = RouteCorridor.tileCount(boxes, 14, 14)
        val z13 = RouteCorridor.tileCount(boxes, 13, 13)
        assertTrue(z14 > z13 * 1.5 && z14 < z13 * 4, "z13 $z13, z14 $z14")
    }
}
