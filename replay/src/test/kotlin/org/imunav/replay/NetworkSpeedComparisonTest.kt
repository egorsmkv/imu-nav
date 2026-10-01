package org.imunav.replay

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.record.TripEvent
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paired JNI replays isolate cell-speed learning from the existing position-only corrections. */
class NetworkSpeedComparisonTest {
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))
    private val points = (0..12_000 step 20).map { projection.toGeo(0.0, it.toDouble()) }
    private val route = Route(points, listOf(Step("depart", null, "test", 12_000.0, 800.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex)), 800.0)

    @Test
    fun learnedSpeedLimitsDriftAfterCellCoverageDisappears() {
        val events = drive(cellUntilS = 220)
        val baseline = NativeComparison(nativeNetworkSpeedEnabled = false).replay(events, 60.0)
        val corrected = NativeComparison(nativeNetworkSpeedEnabled = true).replay(events, 60.0)
        println("cell dropout position-only\n${baseline.summary()}cell dropout with speed\n${corrected.summary()}")
        assertTrue(baseline.nativeBlind.p95M > 700.0, baseline.summary())
        assertTrue(corrected.nativeBlind.p95M < 150.0, corrected.summary())
        assertTrue(corrected.nativeBlind.p95M < baseline.nativeBlind.p95M * 0.25)
        assertEquals(baseline.samples.map { it.kotlinS }, corrected.samples.map { it.kotlinS })
    }

    @Test
    fun continuousCellsRecoverAfterAbruptSpeedChanges() {
        for (changingSpeed in listOf(false, true)) {
            val events = drive(cellUntilS = 600, changingSpeed = changingSpeed)
            val baseline = NativeComparison(nativeNetworkSpeedEnabled = false).replay(events, 60.0)
            val corrected = NativeComparison(nativeNetworkSpeedEnabled = true).replay(events, 60.0)
            println("continuous cells changing=$changingSpeed position-only\n${baseline.summary()}with speed\n${corrected.summary()}")
            if (changingSpeed) {
                assertTrue(corrected.nativeBlind.p95M <= baseline.nativeBlind.p95M, corrected.summary())
                assertTrue(corrected.nativeBlind.p95M < 100.0, corrected.summary())
            } else {
                assertTrue(corrected.nativeBlind.p95M < 80.0, corrected.summary())
            }
            assertEquals(baseline.samples.map { it.kotlinS }, corrected.samples.map { it.kotlinS })
        }
    }

    @Test
    fun slowdownRecoveryDoesNotDependOnOneBatchAlignment() {
        for (phaseS in 0..35 step 5) {
            val events = drive(cellUntilS = 600, changingSpeed = true, slowdownAtS = 400 + phaseS)
            val corrected = NativeComparison(nativeNetworkSpeedEnabled = true).replay(events, 60.0)
            println("slowdown phase=$phaseS\n${corrected.summary()}")
            assertTrue(corrected.nativeBlind.p95M < 150.0, corrected.summary())
        }
    }

    @Test
    fun fixedCellBiasDoesNotBecomeSpeedBias() {
        val events = drive(cellUntilS = 220, biasM = 100.0)
        val baseline = NativeComparison(nativeNetworkSpeedEnabled = false).replay(events, 60.0)
        val corrected = NativeComparison(nativeNetworkSpeedEnabled = true).replay(events, 60.0)
        assertTrue(corrected.nativeBlind.p95M < 250.0, corrected.summary())
        assertTrue(corrected.nativeBlind.p95M < baseline.nativeBlind.p95M * 0.5)
    }

    @Test
    fun freshGpsAndVehicleSpeedDoNotChangeWhenCellSpeedIsEnabled() {
        for (obd in listOf(false, true)) {
            val events = drive(cellUntilS = 600, obd = obd)
            val cutoff = if (obd) 60.0 else null
            val baseline = NativeComparison(nativeNetworkSpeedEnabled = false).replay(events, cutoff)
            val corrected = NativeComparison(nativeNetworkSpeedEnabled = true).replay(events, cutoff)
            assertEquals(baseline.samples.map { it.nativeS }, corrected.samples.map { it.nativeS })
        }
    }

    @Test
    fun defaultPolicyRemainsPositionOnly() {
        val events = drive(cellUntilS = 600, changingSpeed = true)
        val defaults = NativeComparison().replay(events, 60.0)
        val positionOnly = NativeComparison(nativeNetworkSpeedEnabled = false).replay(events, 60.0)
        assertEquals(positionOnly.samples, defaults.samples)
    }

    /** Independent GPS is withheld after warm-up; noisy cells stop early or cover later speed changes. */
    private fun drive(cellUntilS: Int, changingSpeed: Boolean = false, biasM: Double = 0.0, obd: Boolean = false, slowdownAtS: Int = 400): List<TripEvent> = buildList {
        add(TripEvent.Start(START_MS, points.last(), emptyList(), 5.0))
        add(TripEvent.RouteSet(START_MS, route))
        var distanceM = 0.0
        val noiseM = listOf(-12.0, 8.0, 4.0, -8.0, 10.0, -4.0, 0.0)
        for (second in 1..600) {
            val speedMps = when {
                second < 60 -> 15.0
                changingSpeed && second >= slowdownAtS -> 12.0
                changingSpeed && second >= 250 -> 20.0
                else -> 17.0
            }
            distanceM += speedMps
            val offsetMs = second * 1000L
            val point = projection.toGeo(0.0, distanceM)
            add(TripEvent.Fix(RawFix(FixSource.GPS, WALL_MS + offsetMs, START_MS + offsetMs, point.lat, point.lon, 150.0, speedMps.toFloat(), 0f, 4f, 3f, 0.3f)))
            if (obd) add(TripEvent.VehicleSpeed(START_MS + offsetMs, (speedMps * 3.6).toFloat()))
            if (second in 70..cellUntilS && second % 5 == 0) {
                val cell = projection.toGeo(0.0, distanceM + biasM + noiseM[(second / 5) % noiseM.size])
                add(TripEvent.Fix(RawFix(FixSource.CELL, WALL_MS + offsetMs, START_MS + offsetMs, cell.lat, cell.lon, accuracyM = 40f)))
            }
        }
        add(TripEvent.Stop(START_MS + 600_001))
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val WALL_MS = 1_700_000_000_000L
    }
}
