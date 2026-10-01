package org.imunav.replay

import org.imunav.app.nativecore.NativeEstimatorBridge
import org.imunav.app.nativecore.NativeNavigationEstimator
import org.imunav.app.nativecore.NativeRouteGeometry
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.JudgedFix
import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.Verdict
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercise the exact app bridge and JNI wire contract as a live position owner on the host JVM. */
class LiveNativeEstimatorTest {
    private val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.1, 30.0)), emptyList(), 600.0)
    private val empty = PositioningSnapshot(null, null, null, null, GpsState.LOST, false, null)

    @Test
    fun liveBridgePublishesTheSamePredictionAsTheNativeEstimator() {
        NativeRouteGeometry.create(route).use { geometry ->
            val bridge = NativeEstimatorBridge {}
            try {
                bridge.start(geometry, 0.0, 10.0, 20.0, TravelMode.CAR, 1000)
                NativeNavigationEstimator.create(geometry, 0.0, 10.0, 20.0, 6.0, 0.0, TravelMode.CAR, 1000).use { reference ->
                    val engine = NavigationEngine(listener = object : NavListener {}, nativeEstimator = bridge)
                    engine.start(route, route.geometry.last(), nowMs = 1000, estimator = NavigationEstimator.NATIVE_KALMAN)
                    for (time in 1500L..5000L step 500) {
                        bridge.onVehicleSpeed(36.0, time)
                        reference.onVehicleSpeed(36.0, time)
                        engine.onVehicleSpeed(36.0, time)
                        val expected = reference.tick(time, null)
                        engine.tick(time, empty)
                        assertEquals(expected.positionM, engine.progressS, 1e-9)
                        assertEquals(expected.safetyRadiusM, engine.state.uncertaintyM, 1e-9)
                        assertEquals((expected.speedMps * 3.6).toFloat(), engine.state.speedKmh)
                    }
                    assertTrue(engine.progressS > 30.0)
                    bridge.close()
                    val lastPosition = engine.progressS
                    engine.tick(5500, empty)
                    assertEquals(lastPosition, engine.progressS)
                    assertEquals(PositionSource.NONE, engine.state.source)
                }
            } finally {
                bridge.close()
            }
        }
    }

    @Test
    fun jniReportsAcceptedRejectedAndDuplicateGpsPositions() {
        NativeRouteGeometry.create(route).use { geometry ->
            NativeNavigationEstimator.create(geometry, 0.0, 10.0, 20.0, 6.0, 0.0, TravelMode.CAR, 1000).use { estimator ->
                val point = route.pointAt(10.0).point
                val good = JudgedFix(RawFix(FixSource.GPS, 2000, 2000, point.lat, point.lon, accuracyM = 5f, speedMps = 10f), Verdict.GOOD)
                assertTrue(estimator.tick(2000, good).gpsPositionAccepted)
                assertFalse(estimator.tick(2500, good).gpsPositionAccepted)
                val distant = good.copy(fix = good.fix.copy(elapsedMs = 3000, lat = 50.05))
                assertFalse(estimator.tick(3000, distant).gpsPositionAccepted)
            }
        }
    }
}
