package org.imunav.replay

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.record.TripEvent
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Signed turn regressions cover the recorded IMU detector, JNI and geometry matching together. */
class TurnComparisonTest {
    @Test
    fun isolatedLeftAndRightTurnsReduceBlindPositionError() {
        for (direction in listOf(-1.0, 1.0)) {
            val events = drive(Scenario.TURN, direction)
            val baseline = NativeComparison(nativeTurnsEnabled = false).replay(events, 60.0)
            val corrected = NativeComparison().replay(events, 60.0)
            assertTrue(corrected.nativeBlind.count >= 35)
            assertTrue(corrected.nativeBlind.p95M < baseline.nativeBlind.p95M - 7.0, corrected.summary())
            val improvement = baseline.samples.last().nativeErrorM - corrected.samples.last().nativeErrorM
            assertTrue(improvement in 7.0..30.0, "bounded improvement=$improvement")
            assertEquals(baseline.samples.map { it.kotlinS }, corrected.samples.map { it.kotlinS })
            assertEquals(
                baseline.samples.filter { it.elapsedMs < START_MS + 82_000 }.map { it.nativeS },
                corrected.samples.filter { it.elapsedMs < START_MS + 82_000 }.map { it.nativeS },
            )
            println("turn direction=$direction baseline\n${baseline.summary()}turn enabled\n${corrected.summary()}")
        }
    }

    @Test
    fun phoneSwingOppositeYawAndMissedTurnCannotForceASnap() {
        for (scenario in listOf(Scenario.PHONE_SWING, Scenario.WRONG_DIRECTION, Scenario.MISSED_TURN, Scenario.TILT)) {
            val events = drive(scenario, 1.0)
            val baseline = NativeComparison(nativeTurnsEnabled = false).replay(events, 60.0)
            val result = NativeComparison().replay(events, 60.0)
            assertTrue(result.samples.isNotEmpty())
            assertEquals(baseline.samples.map { it.nativeS }, result.samples.map { it.nativeS }, scenario.name)
        }
    }

    @Test
    fun freshGoodGpsNeedsNoTurnCorrection() {
        val events = drive(Scenario.TURN, 1.0)
        val baseline = NativeComparison(nativeTurnsEnabled = false).replay(events)
        val result = NativeComparison().replay(events)
        assertEquals(baseline.samples.map { it.nativeS }, result.samples.map { it.nativeS })
    }

    /** GPS disappears at 60 s; actual speed falls to 8 m/s while DR carries 10 m/s into a turn at 80 s. */
    private fun drive(scenario: Scenario, direction: Double): List<TripEvent> {
        val projection = LocalProjection(GeoPoint(50.45, 30.52))
        val points = (0..760 step 20).map { projection.toGeo(0.0, it.toDouble()) } +
            (20..1000 step 20).map { projection.toGeo(direction * it, 760.0) }
        val route = Route(points, listOf(Step("depart", null, "test", 1760.0, 176.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex)), 176.0)
        return buildList {
            add(TripEvent.Start(START_MS, points.last(), emptyList(), 5.0))
            add(TripEvent.RouteSet(START_MS, route))
            for (offsetMs in 20L..100_000L step 20) {
                val elapsedMs = START_MS + offsetMs
                val turning = offsetMs in 78_020..82_000
                val yaw = when (scenario) {
                    Scenario.TURN, Scenario.TILT -> if (turning) direction * 22.5 else 0.0
                    Scenario.WRONG_DIRECTION -> if (turning) -direction * 22.5 else 0.0
                    Scenario.PHONE_SWING -> if (offsetMs in 80_000..80_500) 180.0 else 0.0
                    Scenario.MISSED_TURN -> 0.0
                }
                val extra = if (scenario == Scenario.TILT && turning) 40.0 else 0.0
                val gyro = ((abs(yaw) + extra) * PI / 180.0).toFloat()
                add(TripEvent.Imu(ImuSample(elapsedMs, null, yaw.toFloat(), floatArrayOf(1f, 0f, 0f), floatArrayOf(gyro, 0f, 0f))))
                if (offsetMs % 1000L == 0L) {
                    val distance = if (offsetMs <= 60_000) offsetMs / 100.0 else 600.0 + (offsetMs - 60_000) * 0.008
                    val followsTurn = distance > 760.0 && scenario != Scenario.MISSED_TURN
                    val point = if (followsTurn) projection.toGeo(direction * (distance - 760.0), 760.0) else projection.toGeo(0.0, distance)
                    val bearing = if (followsTurn) {
                        if (direction > 0) 90f else 270f
                    } else {
                        0f
                    }
                    val speed = if (offsetMs < 60_000) 10f else 8f
                    add(TripEvent.Fix(RawFix(FixSource.GPS, WALL_MS + offsetMs, elapsedMs, point.lat, point.lon, 150.0, speed, bearing, 4f, 3f, 0.3f)))
                }
            }
            add(TripEvent.Stop(START_MS + 100_001))
        }
    }

    private enum class Scenario { TURN, PHONE_SWING, WRONG_DIRECTION, MISSED_TURN, TILT }

    private companion object {
        const val START_MS = 1_000_000L
        const val WALL_MS = 1_700_000_000_000L
    }
}
