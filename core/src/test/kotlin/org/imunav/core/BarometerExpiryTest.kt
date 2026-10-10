package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.ElevationMatcher
import org.imunav.core.route.Route
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic height traces test data continuity separately from terrain-search heuristics. */
class BarometerExpiryTest {
    private fun history(): ElevationMatcher = ElevationMatcher().also { matcher ->
        for (index in 0..50) {
            matcher.onPressure(1000.0 - index * 0.1, index * 1000L)
            matcher.onTravel(index * 10.0, index * 1000L)
        }
        assertEquals(500.0, matcher.windowM)
    }

    @Test
    fun expiredPressureCannotExtendHistoryAndDelayedInputCannotReviveIt() {
        val matcher = history()
        matcher.onTravel(510.0, 50_000 + ElevationMatcher.PRESSURE_MAX_AGE_MS)
        assertEquals(510.0, matcher.windowM)
        matcher.onTravel(520.0, 50_001 + ElevationMatcher.PRESSURE_MAX_AGE_MS)
        assertEquals(0.0, matcher.windowM)
        assertNull(matcher.heightM)
        matcher.onPressure(1000.0, 49_000)
        matcher.onPressure(1000.0, 50_000)
        matcher.onTravel(600.0, 54_000)
        assertEquals(0.0, matcher.windowM)
        assertNull(matcher.heightM)
    }

    @Test
    fun firstPressureAfterGapStartsFreshEvenWithoutInterveningTravelTicks() {
        val matcher = history()
        matcher.onPressure(900.0, 60_000)
        assertEquals(0.0, matcher.windowM)
        assertEquals(ElevationMatcher.heightFromPressure(900.0), assertNotNull(matcher.heightM), 1e-9)
        matcher.onTravel(1000.0, 60_000)
        matcher.onPressure(899.9, 61_000)
        matcher.onTravel(1010.0, 61_000)
        assertEquals(10.0, matcher.windowM, "missing distance must not join the old trace")
    }

    @Test
    fun futureAndInvalidReadingsCannotCreateFreshTravelHistory() {
        val matcher = ElevationMatcher()
        matcher.onPressure(1000.0, 1000)
        matcher.onTravel(0.0, 999)
        matcher.onTravel(10.0, 1000)
        assertEquals(0.0, matcher.windowM)
        matcher.onPressure(Double.NaN, 3001)
        matcher.onTravel(20.0, 3001)
        assertNull(matcher.heightM)
        assertEquals(0.0, matcher.windowM)
    }

    @Test
    fun aPreviouslyMatchableTraceCannotSnapAfterExpiryOrOneResumedSample() {
        val matcher = ElevationMatcher()
        val points = (0..60).map { GeoPoint(50.0 + it * 0.0002, 30.0) }
        val geometry = Route(points, emptyList(), 60.0)
        val heights = DoubleArray(points.size)
        for (index in points.indices) {
            val height = 150.0 + 15.0 * sin(index * 0.17) + 6.0 * sin(index * 0.43)
            matcher.onPressure(1013.25 * (1.0 - height / 44_330.0).pow(5.255), index * 1000L)
            heights[index] = assertNotNull(matcher.heightM)
            // Retain a shorter trace so the match can compare distinct candidate positions.
            if (index >= 20) matcher.onTravel(geometry.cumulative[index], index * 1000L)
        }
        val route = Route(points, emptyList(), 60.0, elevationM = heights)
        val valid = assertNotNull(matcher.match(route, route.length, 150.0, 60_000, listOf(1.0)))
        assertTrue(valid.rmsM < 1e-6)
        // No onTravel call: matching itself must expire the old trace.
        assertNull(matcher.match(route, route.length, 0.0, 63_000, listOf(1.0)))
        matcher.onPressure(980.0, 64_000)
        matcher.onTravel(route.length + 100.0, 64_000)
        assertNull(matcher.match(route, route.length, 0.0, 64_000, listOf(1.0)))
        assertEquals(0.0, matcher.windowM)
    }
}
