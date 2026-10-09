package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.nav.ElevationMatcher
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** Timing regressions use monotonic milliseconds and synthetic inputs, without wall-clock waits. */
class SensorTimeOrderTest {
    private fun engine(): NavigationEngine {
        val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.1, 30.0)), emptyList(), 1000.0)
        return NavigationEngine().also { it.start(route, route.geometry.last(), nowMs = 1000) }
    }

    @Test
    fun delayedAndDuplicateVehicleSamplesCannotReplaceLatestSpeed() {
        val engine = engine()
        val hub = PositioningHub()
        engine.onVehicleSpeed(36.0, 1000)
        engine.tick(1000, hub.snapshot(1000))
        engine.onVehicleSpeed(0.0, 999)
        engine.onVehicleSpeed(0.0, 1000)
        engine.tick(2000, hub.snapshot(2000))
        assertEquals(10.0, engine.state.s, 1e-6)
        assertEquals(PositionSource.DR_OBD, engine.state.source)
    }

    @Test
    fun rejectedVehicleValueDoesNotAdvanceItsClock() {
        val engine = engine()
        val hub = PositioningHub()
        engine.onVehicleSpeed(Double.NaN, 5000)
        engine.onVehicleSpeed(36.0, 1000)
        engine.tick(1000, hub.snapshot(1000))
        engine.tick(2000, hub.snapshot(2000))
        assertEquals(10.0, engine.state.s, 1e-6)
    }

    @Test
    fun oldAndRepeatedTicksDoNotPublishOrInflateNextInterval() {
        val engine = engine()
        val hub = PositioningHub()
        engine.onVehicleSpeed(36.0, 1000)
        val initial = engine.state
        engine.tick(999, hub.snapshot(999))
        assertSame(initial, engine.state)
        engine.tick(1000, hub.snapshot(1000))
        engine.tick(2000, hub.snapshot(2000))
        val state = engine.state
        engine.tick(1500, hub.snapshot(1500))
        engine.tick(2000, hub.snapshot(2000))
        assertSame(state, engine.state)
        engine.tick(2500, hub.snapshot(2500))
        assertEquals(15.0, engine.state.s, 1e-6)
    }

    @Test
    fun vehicleFreshnessIncludesBoundaryButExcludesFutureAndExpiredSamples() {
        val engine = engine()
        val hub = PositioningHub()
        engine.onVehicleSpeed(36.0, 2000)
        engine.tick(1000, hub.snapshot(1000))
        assertNotEquals(PositionSource.DR_OBD, engine.state.source)
        engine.tick(4500, hub.snapshot(4500))
        assertEquals(PositionSource.DR_OBD, engine.state.source)
        engine.tick(4501, hub.snapshot(4501))
        assertNotEquals(PositionSource.DR_OBD, engine.state.source)
    }

    @Test
    fun longPauseKeepsExistingFiveSecondIntegrationCap() {
        val engine = engine()
        val hub = PositioningHub()
        engine.tick(1000, hub.snapshot(1000))
        engine.onVehicleSpeed(36.0, 61_000)
        engine.tick(61_000, hub.snapshot(61_000))
        assertEquals(50.0, engine.state.s, 1e-6)
    }

    @Test
    fun delayedPressureCannotChangeNextFilterInterval() {
        val expected = ElevationMatcher()
        val actual = ElevationMatcher()
        for (matcher in listOf(expected, actual)) {
            matcher.onPressure(1000.0, 1000)
            matcher.onPressure(990.0, 2000)
        }
        val height = actual.heightM
        actual.onPressure(950.0, 1500)
        actual.onPressure(950.0, 2000)
        actual.onPressure(950.0, -1)
        assertEquals(height, actual.heightM)
        expected.onPressure(980.0, 3000)
        actual.onPressure(980.0, 3000)
        assertEquals(expected.heightM, actual.heightM)
        actual.reset()
        actual.onPressure(1000.0, 0)
        val fresh = ElevationMatcher().also { it.onPressure(1000.0, 0) }
        assertEquals(fresh.heightM, actual.heightM)
    }
}
