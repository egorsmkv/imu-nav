package org.imunav.replay

import org.imunav.app.nativecore.NativeEstimatorBridge
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
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import java.io.Closeable
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app's native walking path must consume recorded steps, not merely expose a settings chip. */
class NativeWalkingTest {
    private val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.02, 30.0)), emptyList(), 1500.0)
    private val empty = PositioningSnapshot(null, null, null, null, GpsState.LOST, false, null)

    @Test
    fun liveWalkingAdvancesFromStepsStopsAndRestartsWithoutObd() {
        Session().use { session ->
            val engine = session.engine
            engine.tick(1000, empty)
            for (time in 1500L..61_000L step 500) {
                engine.onStep(time)
                engine.onVehicleSpeed(100.0, time)
                session.bridge.onVehicleSpeed(100.0, time)
                engine.tick(time, empty)
            }
            assertEquals(NavigationEstimator.NATIVE_KALMAN, engine.estimator)
            assertEquals(TravelMode.FOOT, engine.state.travelMode)
            assertTrue(abs(engine.progressS - 120 * 0.72) < 2.0, "s=${engine.progressS}")
            assertEquals((1.44 * 3.6).toFloat(), engine.state.speedKmh)
            assertFalse(engine.state.source.isGps)
            // An app pause cannot integrate the last cadence for a whole minute.
            val beforeGap = engine.progressS
            engine.tick(121_000, empty)
            assertTrue(engine.progressS - beforeGap <= 3.61)
            assertEquals(0f, engine.state.speedKmh)
            val stopped = engine.progressS
            engine.tick(122_000, empty)
            assertEquals(stopped, engine.progressS)
            for (time in 122_500L..125_000L step 500) {
                engine.onStep(time)
                engine.tick(time, empty)
            }
            assertTrue(engine.progressS > stopped + 2.0)
        }
    }

    @Test
    fun nativeWalkingLearnsStrideWithGpsThenTracksABlindWalk() {
        Session().use { session ->
            val engine = session.engine
            for (time in 1500L..181_000L step 500) {
                engine.onStep(time)
                val distance = (time - 1000) / 1000.0 * 1.6
                val snapshot = if (time <= 61_000 && time % 1000 == 0L) {
                    val fix = fix(time, distance)
                    empty.copy(lastUsableGps = JudgedFix(fix, Verdict.GOOD), lastGoodGps = fix, gpsState = GpsState.OK)
                } else {
                    empty
                }
                engine.tick(time, snapshot)
            }
            assertTrue(abs(engine.pedometer.strideM - 0.8) < 0.01)
            assertTrue(abs(engine.progressS - 180 * 1.6) < 5.0, "s=${engine.progressS}")
        }
    }

    @Test
    fun absentSensorsHoldBothNewAndRestoredNativeWalkingTrips() {
        Session().use { session ->
            session.engine.tick(10_000, empty)
            assertEquals(0.0, session.engine.progressS)
            session.bridge.start(session.geometry, 200.0, 10.0, 100.0, TravelMode.FOOT, 11_000)
            session.engine.start(route, route.geometry.last(), nowMs = 11_000, mode = TravelMode.FOOT, estimator = NavigationEstimator.NATIVE_KALMAN)
            session.engine.resumeAt(200.0)
            session.engine.tick(12_000, empty)
            assertEquals(200.0, session.engine.progressS)
            assertEquals(0f, session.engine.state.speedKmh)
        }
    }

    @Test
    fun pairedReplayUsesRecordedStepsEvenWithCarMotionDisabled() {
        val events = buildList {
            add(TripEvent.Start(1000, route.geometry.last(), emptyList(), 5.0))
            add(TripEvent.Mode(1000, TravelMode.FOOT))
            add(TripEvent.Estimator(1000, NavigationEstimator.NATIVE_KALMAN))
            add(TripEvent.RouteSet(1000, route))
            for (time in 1500L..181_000L step 500) {
                add(TripEvent.StepTaken(time))
                if (time % 1000 == 0L) add(TripEvent.Fix(fix(time, (time - 1000) / 1000.0 * 1.6)))
            }
        }.map { requireNotNull(TripFormat.decode(TripFormat.encode(it))) }
        val result = NativeComparison(nativeMotionEnabled = false).replay(events, hideGpsAfterS = 60.0)
        assertTrue(result.blindSamples.size >= 100)
        assertTrue(result.nativeBlind.p95M < 5.0, result.summary())
        val withoutSteps = NativeComparison(nativeMotionEnabled = false).replay(events.filterNot { it is TripEvent.StepTaken }, hideGpsAfterS = 60.0)
        assertTrue(withoutSteps.nativeBlind.p95M > 100.0, "removing recorded steps must change the blind prediction")
    }

    private fun fix(time: Long, distance: Double): RawFix {
        val point = route.pointAt(distance).point
        return RawFix(FixSource.GPS, 1_700_000_000_000L + time, time, point.lat, point.lon, accuracyM = 5f, speedMps = 1.6f, speedAccuracyMps = 0.2f)
    }

    /** Match AppGraph ownership: geometry outlives the bridge; step callbacks reach the engine. */
    private inner class Session : Closeable {
        val geometry = NativeRouteGeometry.create(route)
        val bridge = NativeEstimatorBridge {}
        val engine = NavigationEngine(listener = object : NavListener {}, nativeEstimator = bridge)

        init {
            bridge.start(geometry, 0.0, 0.0, 5.0, TravelMode.FOOT, 1000)
            engine.start(route, route.geometry.last(), nowMs = 1000, mode = TravelMode.FOOT, estimator = NavigationEstimator.NATIVE_KALMAN)
        }

        override fun close() {
            bridge.close()
            geometry.close()
        }
    }
}
