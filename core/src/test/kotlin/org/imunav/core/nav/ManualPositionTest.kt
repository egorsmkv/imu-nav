package org.imunav.core.nav

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The driver corrects a drifted marker by hand: the engine jumps there, and the event survives a recording. */
class ManualPositionTest {
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))

    /** 2 km straight north, then nothing else: no turns that could snap the marker. */
    private fun straightRoute(): Route {
        val points = (0..2000 step 20).map { projection.toGeo(0.0, it.toDouble()) }
        val steps = listOf(Step("depart", null, "Khreshchatyk", 2000.0, 200.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex))
        return Route(points, steps, 200.0)
    }

    @Test
    fun handPlacedPointMovesMarkerOntoRoute() {
        val logs = ArrayList<String>()
        val reroutes = ArrayList<GeoPoint>()
        val engine = NavigationEngine(
            listener = object : NavListener {
                override fun onLog(message: String) {
                    logs += message
                }

                override fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {
                    reroutes += from
                }
            },
        )
        val route = straightRoute()
        engine.start(route, route.geometry.last(), nowMs = 0, startAccuracyM = 500.0)
        val hub = PositioningHub(wallClock = { 1_700_000_000_000L })
        engine.tick(500, hub.snapshot(500))

        // 15 m beside the road, about 1200 m along it.
        val placed = projection.toGeo(15.0, 1200.0)
        val expected = route.project(placed, 0.0, 0.0, route.length, 0.0).s
        val s = assertNotNull(engine.correctPosition(placed, accuracyM = 50.0, nowMs = 1_000))
        assertTrue(abs(s - expected) < 1.0, "s=$s expected=$expected")
        assertTrue(abs(engine.state.s - expected) < 1.0)
        assertEquals(50.0, engine.state.uncertaintyM)
        assertTrue(reroutes.isEmpty(), "a point beside the road is not a deviation")
        assertTrue(logs.any { it.startsWith("manual_position ") && "to_s=${expected.toInt()}" in it }, "logs=$logs")

        // The next tick keeps the corrected position instead of falling back to the coarse start accuracy.
        engine.tick(1_500, hub.snapshot(1_500))
        assertTrue(engine.state.s >= expected - 1.0, "after tick s=${engine.state.s}")
        assertTrue(engine.state.uncertaintyM < 100.0, "uncertainty=${engine.state.uncertaintyM}")
    }

    @Test
    fun pointFarFromRouteAsksForNewRoute() {
        val reroutes = ArrayList<GeoPoint>()
        val engine = NavigationEngine(
            listener = object : NavListener {
                override fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {
                    reroutes += from
                }
            },
        )
        val route = straightRoute()
        engine.start(route, route.geometry.last(), nowMs = 0)
        val away = projection.toGeo(800.0, 600.0)
        engine.correctPosition(away, accuracyM = 50.0, nowMs = 1_000)
        assertEquals(listOf(away), reroutes)
    }

    @Test
    fun withoutNavigationNothingHappens() {
        val engine = NavigationEngine(listener = object : NavListener {})
        assertNull(engine.correctPosition(GeoPoint(50.45, 30.52), accuracyM = 50.0, nowMs = 0))
    }

    @Test
    fun manualPositionRoundTripsThroughRecording() {
        val event = TripEvent.ManualPosition(1234L, GeoPoint(50.4501234, 30.5234567), 50.0)
        val line = TripFormat.encode(event)
        assertEquals("C,1234,50.4501234,30.5234567,50", line)
        assertEquals(event, TripFormat.decode(line))
        assertNull(TripFormat.decode("C,1234,50.45,30.52,-1"), "negative accuracy is malformed")
    }
}
