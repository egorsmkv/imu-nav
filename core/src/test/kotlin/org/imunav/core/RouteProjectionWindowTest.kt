package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Analytical straight-line cases and ambiguous geometry protect the local progress window. */
class RouteProjectionWindowTest {
    @Test
    fun longSegmentClampsToArcWindowIncludingBackwardMotion() {
        val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.02, 30.0)), emptyList(), 0.0)
        // An independent straight-line oracle: latitude fraction equals arc-length fraction.
        for (center in listOf(0.0, 0.25, 0.5, 1.0)) {
            for (radius in listOf(0.0, 0.1, 1.0)) {
                for (query in listOf(0.9, 0.6, 0.3, 0.0)) {
                    val lower = (center - radius).coerceAtLeast(0.0)
                    val upper = (center + radius).coerceAtMost(1.0)
                    val expected = query.coerceIn(lower, upper)
                    val result = route.project(
                        GeoPoint(50.0 + 0.02 * query, 30.0),
                        center * route.length,
                        radius * route.length,
                        radius * route.length,
                        1e9,
                    )
                    assertEquals(expected * route.length, result.s, 1e-6)
                    assertEquals(50.0 + 0.02 * expected, result.point.lat, 1e-10)
                    assertEquals(abs(query - expected) * 0.02 * 110_540.0, result.offsetM, 1e-6)
                }
            }
        }
    }

    @Test
    fun globalFallbackStillEscapesWindowInsideASingleSegment() {
        val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.02, 30.0)), emptyList(), 0.0)
        val query = GeoPoint(50.018, 30.0)
        val local = route.project(query, route.length * 0.5, 0.0, 0.0, 1e9)
        val global = route.project(query, route.length * 0.5, 0.0, 0.0, 20.0)
        assertEquals(route.length * 0.5, local.s, 1e-6)
        assertEquals(route.length * 0.9, global.s, 1e-6)
        assertTrue(global.offsetM < 1e-6)
    }

    @Test
    fun repeatedVerticesLoopsAndParallelReturnsRespectWindow() {
        for (returnLongitude in listOf(30.0, 30.0001)) {
            val route = Route(
                listOf(GeoPoint(50.0, 30.0), GeoPoint(50.02, 30.0), GeoPoint(50.02, 30.0), GeoPoint(50.02, returnLongitude), GeoPoint(50.0, returnLongitude)),
                emptyList(),
                0.0,
            )
            val firstLength = route.cumulative[1]
            val result = route.project(GeoPoint(50.005, returnLongitude), firstLength * 0.8, firstLength * 0.1, firstLength * 0.1, 1e9)
            assertEquals(firstLength * 0.7, result.s, 1e-6)
            assertEquals(0, result.segment)
        }
        val point = GeoPoint(50.0, 30.0)
        val collapsed = Route(listOf(point, point, point), emptyList(), 0.0).project(point, 0.0, 0.0, 0.0, 1e9)
        assertEquals(0.0, collapsed.s)
        assertEquals(0.0, collapsed.offsetM)
    }
}
