package org.imunav.core

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Short date-line segments must stay short in interpolation, headings and bounded projection. */
class DateLineGeometryTest {
    @Test
    fun eastAndWestCrossingsInterpolateAndProjectOnTheShortArc() {
        for (direction in listOf(1.0, -1.0)) {
            val start = GeoPoint(10.0, direction * 179.999)
            val end = GeoPoint(10.0, -direction * 179.999)
            val route = Route(listOf(start, end), emptyList(), 20.0)
            assertTrue(route.length in 218.0..220.0)
            val midpoint = route.pointAt(route.length / 2).point
            assertEquals(0.0, Geo.longitudeDelta(180.0, midpoint.lon), 1e-9)
            assertEquals(if (direction > 0) 90.0 else 270.0, route.bearingAt(route.length / 2), 1e-9)
            val projection = route.project(GeoPoint(10.0, 180.0), route.length / 2, route.length, route.length, 120.0)
            assertEquals(route.length / 2, projection.s, 1e-5)
            assertTrue(projection.offsetM < 1e-5)
            val clipped = route.project(GeoPoint(10.0, 180.0), route.length / 4, 0.0, 0.0, Double.MAX_VALUE)
            assertEquals(route.length / 4, clipped.s, 1e-9)
            assertTrue(clipped.point.lon in -180.0..180.0)
            assertEquals(direction * 179.9995, clipped.point.lon, 1e-9)
            val far = route.project(GeoPoint(10.0, 0.0), route.length / 2, route.length, route.length, 120.0)
            assertTrue(far.offsetM > 10_000_000, "the crossing cannot become a segment through longitude zero")
        }
    }
}
