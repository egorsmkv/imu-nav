package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.JudgedFix
import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.gnss.Verdict
import org.imunav.core.nav.NavAlert
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.PositionSource
import org.imunav.core.nav.RouteEstimate
import org.imunav.core.nav.RouteEstimateProvider
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripReplayer
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** State ownership must reach guidance, not just the map marker or a comparison log. */
class NativeNavigationTest {
    private val points = listOf(GeoPoint(50.0, 30.0), GeoPoint(50.005, 30.0), GeoPoint(50.01, 30.0))
    private val route = Route(points, listOf(Step("turn", "right", "Test", 0.0, 0.0, 1), Step("arrive", null, "", 0.0, 0.0, 2)), 100.0)
    private val empty = PositioningSnapshot(null, null, null, null, GpsState.LOST, false, null)
    private val alerts = mutableListOf<NavAlert>()
    private val listener = object : NavListener {
        override fun onAlert(alert: NavAlert) {
            alerts += alert
        }
    }

    @Test
    fun nativePositionSpeedAndUncertaintyDriveGuidanceWithoutKotlinPrediction() {
        var calls = 0
        val engine = NavigationEngine(
            listener = listener,
            nativeEstimator = RouteEstimateProvider { _, _, _, _ ->
                calls++
                RouteEstimate(500.0, 12.0, 900.0, false)
            },
        )
        engine.start(route, points.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.onVehicleSpeed(100.0, 1500)
        engine.tick(1500, empty)
        engine.tick(2000, empty)
        assertEquals(2, calls)
        assertEquals(500.0, engine.progressS)
        assertEquals(route.pointAt(500.0).point, engine.state.position)
        assertEquals(43.2f, engine.state.speedKmh)
        assertEquals(900.0, engine.state.uncertaintyM, "native safety radius must not be capped by Kotlin drift display")
        assertEquals(route.stepS(0) - 500.0, engine.state.distToNextM)
        assertEquals(route.length - 500.0, engine.state.remainingM)
        assertFalse(engine.state.arrived)
    }

    @Test
    fun arrivalUsesNativePosition() {
        val engine = NavigationEngine(listener = listener, nativeEstimator = RouteEstimateProvider { _, _, _, _ -> RouteEstimate(route.length, 0.0, 20.0, false) })
        engine.start(route, points.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.tick(1500, empty)
        assertTrue(engine.state.arrived)
        assertTrue(NavAlert.ARRIVED in alerts)
    }

    @Test
    fun kotlinDefaultAndWalkingNeverConsumeNativeEstimate() {
        val engine = NavigationEngine(listener = listener, nativeEstimator = RouteEstimateProvider { _, _, _, _ -> error("must not be called") })
        engine.start(route, points.last(), nowMs = 1000)
        engine.tick(1500, empty)
        assertEquals(NavigationEstimator.KOTLIN, engine.estimator)
        engine.stop()
        engine.start(route, points.last(), nowMs = 2000, mode = TravelMode.FOOT, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.tick(2500, empty)
        assertEquals(NavigationEstimator.KOTLIN, engine.estimator)
    }

    @Test
    fun unavailableOrInvalidNativeStateHoldsRestoredPositionAndSuppressesArrival() {
        var estimate: RouteEstimate? = null
        val engine = NavigationEngine(listener = listener, nativeEstimator = RouteEstimateProvider { _, _, _, _ -> estimate })
        engine.start(route, points.last(), nowMs = 1000, startAccuracyM = 400.0, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.resumeAt(route.length - 1.0)
        assertEquals(400.0, engine.state.uncertaintyM)
        engine.tick(1500, empty)
        estimate = RouteEstimate(Double.NaN, 10.0, 1.0, false)
        engine.tick(2000, empty)
        assertEquals(route.length - 1.0, engine.progressS)
        assertEquals(0f, engine.state.speedKmh)
        assertTrue(engine.state.uncertaintyM >= 400.0)
        assertEquals(PositionSource.NONE, engine.state.source)
        assertFalse(engine.state.arrived)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun rejectedGpsDoesNotClaimRestoredGpsOrOverwriteNativePosition() {
        var accepted = false
        val engine = NavigationEngine(listener = listener, nativeEstimator = RouteEstimateProvider { _, _, _, _ -> RouteEstimate(100.0, 10.0, 80.0, accepted) })
        engine.start(route, points.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.tick(2000, snapshot(2000))
        assertFalse(engine.state.source.isGps)
        accepted = true
        engine.tick(3000, snapshot(3000))
        assertEquals(PositionSource.GPS, engine.state.source)
        assertEquals(100.0, engine.progressS)
        assertEquals(80.0, engine.state.uncertaintyM)
        accepted = false
        engine.tick(6500, snapshot(6500))
        assertFalse(engine.state.source.isGps)
    }

    @Test
    fun simulatedLossAndBadGpsNeverReachNative() {
        val engine = NavigationEngine(
            listener = listener,
            nativeEstimator = RouteEstimateProvider { _, positioning, _, _ ->
                assertNull(positioning.lastUsableGps)
                RouteEstimate(0.0, 0.0, 20.0, false)
            },
        )
        engine.start(route, points.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.simulateGpsLoss = true
        engine.tick(2000, snapshot(2000))
        engine.simulateGpsLoss = false
        engine.tick(3000, snapshot(3000, Verdict(TrustLevel.BAD, listOf("mock"))))
    }

    @Test
    fun rejectedGoodGpsStillDetectsLeavingThePlannedRoad() {
        val engine = NavigationEngine(listener = listener, nativeEstimator = RouteEstimateProvider { _, _, _, _ -> RouteEstimate(100.0, 10.0, 80.0, false) })
        engine.autoReroute = false
        engine.start(route, points.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
        for (time in 2000L..30000L step 1000) {
            val fix = RawFix(FixSource.GPS, time, time, 50.001, 30.01, accuracyM = 5f, speedMps = 10f)
            engine.tick(time, empty.copy(lastUsableGps = JudgedFix(fix, Verdict.GOOD)))
        }
        assertTrue(engine.state.offRoute)
        assertTrue(NavAlert.OFF_ROUTE in alerts)
        assertEquals(100.0, engine.progressS)
        assertFalse(engine.state.source.isGps)
    }

    @Test
    fun rerouteKeepsNativeSelectedAndUsesNewRouteForGuidance() {
        val engine = NavigationEngine(listener = listener, nativeEstimator = RouteEstimateProvider { _, _, _, _ -> RouteEstimate(50.0, 10.0, 100.0, false) })
        engine.start(route, points.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
        engine.tick(2000, empty)
        val replacement = Route(points.reversed(), emptyList(), 200.0)
        engine.setRoute(replacement, 3000)
        engine.tick(3500, empty)
        assertEquals(NavigationEstimator.NATIVE_KALMAN, engine.estimator)
        assertEquals(replacement.pointAt(50.0).point, engine.state.position)
    }

    @Test
    fun recordingsRoundTripSelectionAndLegacyReplayRejectsNativeTripsClearly() {
        for (estimator in NavigationEstimator.entries) {
            val event = TripEvent.Estimator(1000, estimator)
            assertEquals(event, TripFormat.decode(TripFormat.encode(event)))
        }
        val error = assertFailsWith<IllegalArgumentException> {
            TripReplayer().replay(listOf(TripEvent.Estimator(1000, NavigationEstimator.NATIVE_KALMAN)))
        }
        assertTrue(error.message.orEmpty().contains("--compare-native"))
        TripReplayer().replay(listOf(TripEvent.Estimator(1000, NavigationEstimator.KOTLIN)))
    }

    private fun snapshot(time: Long, verdict: Verdict = Verdict.GOOD): PositioningSnapshot {
        val fix = RawFix(FixSource.GPS, time, time, 50.001, 30.0, accuracyM = 5f, speedMps = 10f)
        return empty.copy(lastUsableGps = JudgedFix(fix, verdict), lastGoodGps = fix, gpsState = GpsState.OK)
    }
}
