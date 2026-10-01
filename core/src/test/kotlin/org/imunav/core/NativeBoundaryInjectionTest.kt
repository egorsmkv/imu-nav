package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.gnss.RawFix
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NetSample
import org.imunav.core.nav.NetworkPositionTracker
import org.imunav.core.nav.NetworkTracker
import org.imunav.core.route.Projection
import org.imunav.core.route.Route
import org.imunav.core.route.RouteProjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proves Android-native math boundaries are actually consumed by [NavigationEngine]. */
class NativeBoundaryInjectionTest {
    @Test
    fun injectedProjectionAndNetworkGateHandleNetworkFix() {
        val route = Route(
            geometry = listOf(GeoPoint(50.0, 30.0), GeoPoint(50.01, 30.0)),
            steps = emptyList(),
            durationS = 60.0,
        )
        val projector = CountingProjector()
        val tracker = CountingTracker()
        val engine = NavigationEngine(
            listener = object : NavListener {},
            routeProjector = projector,
            networkTracker = tracker,
        )
        engine.start(route, route.geometry.last(), waypoints = listOf(GeoPoint(50.005, 30.0)), nowMs = 0)
        val networkFix = RawFix(
            source = FixSource.NET,
            timeMs = 1_000,
            elapsedMs = 1_000,
            lat = 50.002,
            lon = 30.0,
            accuracyM = 30f,
        )
        engine.tick(
            1_000,
            PositioningSnapshot(null, null, networkFix, null, GpsState.LOST, jammed = false, compassDeg = null),
        )

        assertTrue(projector.calls >= 2, "waypoint and network fix should use the injected projector")
        assertEquals(1, tracker.gateCalls)
        assertEquals(1, tracker.recordCalls)
    }

    private class CountingProjector : RouteProjector {
        var calls = 0

        override fun project(route: Route, point: GeoPoint, aroundS: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection {
            calls++
            return route.project(point, aroundS, behindM, aheadM, globalIfFartherM)
        }
    }

    private class CountingTracker(private val delegate: NetworkTracker = NetworkTracker()) : NetworkPositionTracker by delegate {
        var gateCalls = 0
        var recordCalls = 0

        override fun gate(elapsedMs: Long, s: Double, acc: Double): NetworkTracker.GateResult {
            gateCalls++
            return delegate.gate(elapsedMs, s, acc)
        }

        override fun record(sample: NetSample, lat: Double, lon: Double) {
            recordCalls++
            delegate.record(sample, lat, lon)
        }
    }
}
