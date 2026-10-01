package org.imunav.replay

import org.imunav.app.nativecore.NativeNavigationEstimator
import org.imunav.app.nativecore.NativeRouteGeometry
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.record.TripEvent
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises independent coarse positions through the exact app JNI wrapper and replay timeline. */
class NetworkComparisonTest {
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))
    private val points = (0..6000 step 20).map { projection.toGeo(0.0, it.toDouble()) }
    private val route = Route(points, listOf(Step("depart", null, "test", 6000.0, 400.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex)), 400.0)

    @Test
    fun noisyCellsBoundBlindPositionDrift() {
        val events = drive(Scenario.NOISY)
        val baseline = NativeComparison(nativeNetworkEnabled = false).replay(events, 60.0)
        val corrected = NativeComparison().replay(events, 60.0)
        assertTrue(corrected.nativeBlind.count >= 230)
        assertTrue(baseline.nativeBlind.p95M > 400.0, baseline.summary())
        assertTrue(corrected.nativeBlind.p95M < 80.0, corrected.summary())
        assertTrue(corrected.nativeBlind.p95M < baseline.nativeBlind.p95M * 0.25)
        assertEquals(baseline.samples.map { it.kotlinS }, corrected.samples.map { it.kotlinS })
        println("coarse-position baseline\n${baseline.summary()}coarse-position enabled\n${corrected.summary()}")
    }

    @Test
    fun cachedCellsAndAnIsolatedJumpCannotCorrectNativePosition() {
        for (scenario in listOf(Scenario.CACHED, Scenario.ISOLATED_JUMP)) {
            val events = drive(scenario)
            val baseline = NativeComparison(nativeNetworkEnabled = false).replay(events, 60.0)
            val result = NativeComparison().replay(events, 60.0)
            assertEquals(baseline.samples.map { it.nativeS }, result.samples.map { it.nativeS })
        }
    }

    @Test
    fun freshGoodGpsTakesPrecedenceOverNoisyCells() {
        val events = drive(Scenario.NOISY)
        val baseline = NativeComparison(nativeNetworkEnabled = false).replay(events)
        val result = NativeComparison().replay(events)
        assertTrue(result.samples.size > 250)
        assertEquals(baseline.samples.map { it.nativeS }, result.samples.map { it.nativeS })
    }

    @Test
    fun jniCoarseInputRejectsFusedGpsAndMockButAllowsCellAndNetwork() {
        for (source in FixSource.entries) {
            for (mock in listOf(false, true)) {
                NativeRouteGeometry.create(route).use { geometry ->
                    NativeNavigationEstimator.create(geometry, 0.0, 10.0, 200.0, 2.0, 0.0, TravelMode.CAR, START_MS).use { estimator ->
                        var finalPosition = 0.0
                        for (offsetMs in listOf(1000L, 6000L, 11_000L)) {
                            val point = projection.toGeo(0.0, 200.0 + offsetMs / 100.0)
                            val fix = RawFix(source, WALL_MS + offsetMs, START_MS + offsetMs, point.lat, point.lon, accuracyM = 30f, isMock = mock)
                            finalPosition = estimator.tick(START_MS + offsetMs, null, network = fix).positionM
                        }
                        if (!mock && source in setOf(FixSource.CELL, FixSource.NET)) {
                            assertTrue(finalPosition > 130.0)
                        } else {
                            assertEquals(110.0, finalPosition, 0.001)
                        }
                    }
                }
            }
        }
    }

    /** The car accelerates from 15 to 17 m/s after GPS is hidden; cells arrive every five seconds. */
    private fun drive(scenario: Scenario): List<TripEvent> = buildList {
        add(TripEvent.Start(START_MS, points.last(), emptyList(), 5.0))
        add(TripEvent.RouteSet(START_MS, route))
        var distanceM = 0.0
        for (second in 1..300) {
            val speedMps = if (second < 60) 15.0 else 17.0
            distanceM += speedMps
            val offsetMs = second * 1000L
            val point = projection.toGeo(0.0, distanceM)
            add(TripEvent.Fix(RawFix(FixSource.GPS, WALL_MS + offsetMs, START_MS + offsetMs, point.lat, point.lon, 150.0, speedMps.toFloat(), 0f, 4f, 3f, 0.3f)))
            val cellDue = second >= 70 && second % 5 == 0 && (scenario != Scenario.ISOLATED_JUMP || second == 120)
            if (cellDue) {
                val cellPosition = when (scenario) {
                    Scenario.NOISY -> distanceM + if (second % 10 == 0) 20.0 else -20.0
                    Scenario.CACHED -> 1200.0
                    Scenario.ISOLATED_JUMP -> distanceM + 2000.0
                }
                val cell = projection.toGeo(0.0, cellPosition)
                add(TripEvent.Fix(RawFix(FixSource.CELL, WALL_MS + offsetMs, START_MS + offsetMs, cell.lat, cell.lon, accuracyM = 40f)))
            }
        }
        add(TripEvent.Stop(START_MS + 300_001))
    }

    private enum class Scenario { NOISY, CACHED, ISOLATED_JUMP }

    private companion object {
        const val START_MS = 1_000_000L
        const val WALL_MS = 1_700_000_000_000L
    }
}
