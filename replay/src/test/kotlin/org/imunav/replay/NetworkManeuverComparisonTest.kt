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

/** Covers changing motion and tower errors separately; reference GPS never supplies blind speed. */
class NetworkManeuverComparisonTest {
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))
    private val points = (0..16_000 step 20).map { projection.toGeo(0.0, it.toDouble()) }
    private val route = Route(points, listOf(Step("depart", null, "test", 16_000.0, 900.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex)), 900.0)

    @Test
    fun changingMotionAndCellErrorsHavePairedPeakAndRecoveryMetrics() {
        for (scenario in Scenario.entries) {
            val events = drive(scenario)
            val baseline = NativeComparison(nativeNetworkSpeedEnabled = false).replay(events, 60.0)
            val learned = NativeComparison(nativeNetworkSpeedEnabled = true).replay(events, 60.0)
            println("manoeuvre=$scenario position-only\n${baseline.summary()}cell-speed\n${learned.summary()}")
            println("recovery below 100 m: position-only=${recoverySeconds(baseline)} cell-speed=${recoverySeconds(learned)} s")
            assertTrue(learned.nativeBlind.count >= 530, learned.summary())
            assertTrue(learned.nativeBlind.p95M < scenario.p95LimitM, learned.summary())
            assertTrue(learned.nativeBlind.maxM < scenario.peakLimitM, learned.summary())
            scenario.recoveryLimitS?.let { limit -> assertEquals(true, recoverySeconds(learned)?.let { it <= limit }, learned.summary()) }
            assertEquals(baseline.samples.map { it.kotlinS }, learned.samples.map { it.kotlinS })
        }
    }

    /** Require 30 seconds below the threshold through the end, not a transient crossing; null means unproven. */
    private fun recoverySeconds(result: ComparisonResult): Long? {
        val afterChange = result.blindSamples.filter { it.elapsedMs >= START_MS + CHANGE_MS }
        if (afterChange.last().nativeErrorM >= 100.0) return null
        val lastBad = afterChange.lastOrNull { it.nativeErrorM >= 100.0 } ?: return 0L
        if (afterChange.last().elapsedMs - lastBad.elapsedMs < 30_000) return null
        return (lastBad.elapsedMs - START_MS - CHANGE_MS) / 1000 + 1
    }

    /** Independent noise is deterministic so regressions are reproducible across Rust/JVM hosts. */
    private fun drive(scenario: Scenario): List<TripEvent> = buildList {
        add(TripEvent.Start(START_MS, points.last(), emptyList(), 5.0))
        add(TripEvent.RouteSet(START_MS, route))
        val noiseM = listOf(-12.0, 8.0, 4.0, -8.0, 10.0, -4.0, 0.0)
        val intervalsS = if (scenario == Scenario.IRREGULAR) listOf(5, 10, 5, 15) else listOf(5)
        var nextCellS = 70
        var cellIndex = 0
        var distanceM = 0.0
        for (second in 1..600) {
            val speed = speedAt(second.toDouble(), scenario)
            if (scenario == Scenario.TRAFFIC) {
                for (offsetMs in (second - 1) * 1000L + 20..second * 1000L step 20) {
                    val moving = speedAt(offsetMs / 1000.0, scenario) > 0.0
                    val vibration = if (!moving) {
                        0.03f
                    } else if (offsetMs % 40L == 0L) {
                        1.2f
                    } else {
                        0.8f
                    }
                    val gyro = if (moving) 0.2f else 0.005f
                    add(TripEvent.Imu(ImuSample(START_MS + offsetMs, null, 0f, floatArrayOf(vibration, 0f, 0f), floatArrayOf(gyro, 0f, 0f))))
                }
            }
            distanceM += (speed + speedAt(second - 1.0, scenario)) / 2.0
            val offsetMs = second * 1000L
            val point = projection.toGeo(0.0, distanceM)
            add(TripEvent.Fix(RawFix(FixSource.GPS, WALL_MS + offsetMs, START_MS + offsetMs, point.lat, point.lon, 150.0, speed.toFloat(), 0f, 4f, 3f, 0.3f)))
            if (second == nextCellS) {
                val bias = when (scenario) {
                    Scenario.CHANGING_BIAS -> ((second - 250) * 1.5).coerceIn(0.0, 120.0)
                    Scenario.TOWER_JUMP -> if (second == 300) 150.0 else 0.0
                    else -> 0.0
                }
                val cell = projection.toGeo(0.0, distanceM + bias + noiseM[cellIndex % noiseM.size])
                add(TripEvent.Fix(RawFix(FixSource.CELL, WALL_MS + offsetMs, START_MS + offsetMs, cell.lat, cell.lon, accuracyM = 40f)))
                nextCellS += intervalsS[cellIndex % intervalsS.size]
                cellIndex++
            }
        }
        add(TripEvent.Stop(START_MS + 600_001))
    }

    /** Acceleration moves away from the saved 15 m/s prior; braking is deliberately gradual. */
    private fun speedAt(second: Double, scenario: Scenario): Double = when {
        second < 60 -> 15.0

        second < 250 -> 17.0

        scenario == Scenario.ACCELERATION || scenario == Scenario.IRREGULAR -> 17.0 + ((second - 250) * 0.8).coerceIn(0.0, 8.0)

        scenario == Scenario.BRAKING -> 17.0 - ((second - 250) / 10.0).coerceIn(0.0, 8.0)

        scenario == Scenario.TRAFFIC -> {
            val phase = (second - 250) % 120
            when {
                phase < 5 -> 17.0 * (1.0 - phase / 5.0)
                phase < 45 -> 0.0
                phase < 55 -> 17.0 * (phase - 45.0) / 10.0
                else -> 17.0
            }
        }

        else -> 17.0
    }

    /** Regression ceilings, not acceptable navigation error: sparse cells and drifting bias remain failures. */
    private enum class Scenario(val p95LimitM: Double, val peakLimitM: Double, val recoveryLimitS: Long?) {
        ACCELERATION(180.0, 220.0, 130),
        BRAKING(180.0, 210.0, 270),
        TRAFFIC(120.0, 175.0, 270),
        IRREGULAR(1500.0, 1600.0, null),
        CHANGING_BIAS(150.0, 155.0, null),
        TOWER_JUMP(60.0, 70.0, 0),
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val WALL_MS = 1_700_000_000_000L
        const val CHANGE_MS = 250_000L
    }
}
