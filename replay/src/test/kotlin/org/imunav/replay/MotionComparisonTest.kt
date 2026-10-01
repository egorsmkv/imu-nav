package org.imunav.replay

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.record.TripEvent
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** No-OBD comparisons exercise the real motion detector, JNI transport and Rust model together. */
class MotionComparisonTest {
    @Test
    fun blindTrafficStopAndRestartReduceDriftWithoutGettingStuck() {
        val events = drive(Scenario.TRAFFIC)
        val baseline = NativeComparison(nativeMotionEnabled = false).replay(events, 60.0)
        val improved = NativeComparison().replay(events, 60.0)
        assertTrue(improved.nativeBlind.p95M < baseline.nativeBlind.p95M * 0.2, improved.summary())
        assertTrue(improved.nativeBlind.p95M < 80.0, improved.summary())
        val stopped = improved.samples.filter { it.elapsedMs - START_MS in 80_000..115_000 }
        assertTrue(stopped.size >= 30)
        assertTrue(stopped.maxOf { it.nativeS } - stopped.minOf { it.nativeS } < 1.0)
        assertTrue(improved.samples.last().nativeS > stopped.last().nativeS + 300.0)
        println("no-OBD stop/start baseline\n${baseline.summary()}no-OBD stop/start improved\n${improved.summary()}")
    }

    @Test
    fun briefPhoneMovementDoesNotReleaseAConfirmedStop() {
        val normal = NativeComparison().replay(drive(Scenario.TRAFFIC), 60.0)
        val shaken = NativeComparison().replay(drive(Scenario.PHONE_MOVEMENT), 60.0)
        assertEquals(normal.samples.map { it.nativeS }, shaken.samples.map { it.nativeS })
    }

    @Test
    fun smoothDrivingWithCellMovementCannotBeFrozenByQuietImu() {
        val events = drive(Scenario.SMOOTH_WITH_CELLS)
        val baseline = NativeComparison(nativeMotionEnabled = false).replay(events, 60.0)
        val improved = NativeComparison().replay(events, 60.0)
        assertTrue(improved.nativeBlind.count >= 100)
        assertTrue(improved.nativeBlind.p95M < 10.0, improved.summary())
        assertEquals(baseline.samples.map { it.nativeS }, improved.samples.map { it.nativeS })
    }

    @Test
    fun missingImuDoesNotKeepTheNativeEstimatorPermanentlyStopped() {
        val events = drive(Scenario.TRAFFIC).filterNot {
            it is TripEvent.Imu && it.elapsedMs - START_MS in 85_000..110_000
        }
        val result = NativeComparison().replay(events, 60.0)
        val beforeGap = result.samples.first { it.elapsedMs - START_MS == 85_000L }
        val duringGap = result.samples.first { it.elapsedMs - START_MS == 100_000L }
        assertTrue(duringGap.nativeS > beforeGap.nativeS + 100.0)
    }

    /** One minute visible, then braking, a long stop and independent linear acceleration to cruise. */
    private fun drive(scenario: Scenario): List<TripEvent> {
        val projection = LocalProjection(GeoPoint(50.45, 30.52))
        val points = (0..3000 step 20).map { projection.toGeo(0.0, it.toDouble()) }
        val route = Route(points, listOf(Step("depart", null, "test", 3000.0, 300.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex)), 300.0)
        return buildList {
            add(TripEvent.Start(START_MS, points.last(), emptyList(), 5.0))
            add(TripEvent.RouteSet(START_MS, route))
            var distanceM = 0.0
            var previousSpeedMps = 10.0
            for (offsetMs in 20L..180_000L step 20) {
                val smooth = scenario == Scenario.SMOOTH_WITH_CELLS
                val speedMps = if (smooth) 10.0 else speedAt(offsetMs)
                distanceM += (previousSpeedMps + speedMps) * 0.01
                previousSpeedMps = speedMps
                val phoneMovement = scenario == Scenario.PHONE_MOVEMENT && offsetMs in 95_000..95_180
                val quiet = (smooth || speedMps == 0.0) && !phoneMovement
                val acceleration = if (quiet) {
                    0.03f
                } else if (offsetMs % 40L == 0L) {
                    1.2f
                } else {
                    0.8f
                }
                val gyro = if (quiet) 0.005f else 0.2f
                val elapsedMs = START_MS + offsetMs
                add(TripEvent.Imu(ImuSample(elapsedMs, null, 0f, floatArrayOf(acceleration, 0f, 0f), floatArrayOf(gyro, 0f, 0f))))
                if (offsetMs % 1000L == 0L) {
                    val point = route.pointAt(distanceM).point
                    add(TripEvent.Fix(RawFix(FixSource.GPS, WALL_MS + offsetMs, elapsedMs, point.lat, point.lon, 150.0, speedMps.toFloat(), 0f, 4f, 3f, 0.3f, false)))
                    if (smooth && offsetMs % 5000L == 0L) {
                        add(TripEvent.Fix(RawFix(FixSource.CELL, WALL_MS + offsetMs, elapsedMs, point.lat, point.lon, null, null, null, 20f, null, null, false)))
                    }
                }
            }
            add(TripEvent.Stop(START_MS + 180_001))
        }
    }

    private fun speedAt(offsetMs: Long): Double = when (offsetMs) {
        in 60_000..64_000 -> (64_000 - offsetMs) / 400.0
        in 64_001..120_000 -> 0.0
        in 120_001..130_000 -> (offsetMs - 120_000) / 1000.0
        else -> 10.0
    }

    private enum class Scenario { TRAFFIC, PHONE_MOVEMENT, SMOOTH_WITH_CELLS }

    private companion object {
        const val START_MS = 1_000_000L
        const val WALL_MS = 1_700_000_000_000L
    }
}
