package org.imunav.core.nav

import org.imunav.core.Tuning
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.route.Projection
import org.imunav.core.route.Route
import org.imunav.core.route.RouteCursor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Network accuracy may legitimately be zero; inverse-variance correction must remain finite. */
class NetworkCorrectionTest {
    @Test
    fun underflowedVariancesLeaveRouteProgressUntouched() {
        val tracker = NetworkTracker()
        for ((index, position) in listOf(750.0, 780.0, 700.0).withIndex()) {
            tracker.record(NetSample(index * 1_000L, position, Double.MIN_VALUE, 0.0), 50.0 + index * 0.001, 30.0)
        }
        val cursor = cursor()
        val logs = mutableListOf<String>()
        NetworkCorrection(tracker, logs::add).netBack(cursor, 20_000, 0.0, null)
        assertEquals(1_000.0, cursor.s)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun malformedDeviationEvidenceCannotTriggerAReroute() {
        val cursor = cursor()
        val valid = RawFix(FixSource.NET, 1_000, 1_000, 50.0, 30.0, accuracyM = 30f)
        val projection = Projection(1_000.0, 1_000.0, 0, valid.point)
        for ((fix, projected, accuracy) in listOf(
            Triple(valid, projection, Double.NaN),
            Triple(valid, projection, -1.0),
            Triple(valid, projection.copy(offsetM = Double.NaN), 30.0),
            Triple(valid.copy(lat = Double.NaN), projection, 30.0),
            Triple(valid.copy(lon = 181.0), projection, 30.0),
            Triple(valid.copy(elapsedMs = -1), projection, 30.0),
        )) {
            val detector = NetworkDeviationDetector()
            repeat(3) {
                assertNull(detector.check(cursor, projected, accuracy, fix, false, false, Tuning.DEFAULT))
            }
        }
    }

    private fun cursor() = RouteCursor(Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.1, 30.0)), emptyList(), 100.0)).also { it.moveTo(1_000.0) }

    @Test
    fun zeroAccuracySamplesHaveFiniteCorrectionWithoutPoisoningRouteProgress() {
        val tracker = NetworkTracker()
        for ((index, position) in listOf(750.0, 780.0, 700.0).withIndex()) {
            tracker.record(NetSample(index * 1_000L, position, if (index < 2) 0.0 else 20.0, 0.0), 50.0 + index * 0.001, 30.0)
        }
        val cursor = cursor()
        val logs = mutableListOf<String>()
        NetworkCorrection(tracker, logs::add).netBack(cursor, 20_000, 0.0, null)
        assertTrue(cursor.s.isFinite())
        assertEquals(765.0, cursor.s, 1e-9)
        assertEquals(1, logs.size)
    }

    @Test
    fun positiveAccuracyKeepsItsExistingInverseVarianceCorrection() {
        val tracker = NetworkTracker()
        for ((index, position) in listOf(750.0, 780.0, 700.0).withIndex()) {
            tracker.record(NetSample(index * 1_000L, position, 20.0 * (index + 1), 0.0), 50.0 + index * 0.001, 30.0)
        }
        val cursor = cursor()
        NetworkCorrection(tracker) {}.netBack(cursor, 20_000, 0.0, null)
        val expected = (750.0 / 400.0 + 780.0 / 1600.0 + 700.0 / 3600.0) / (1.0 / 400.0 + 1.0 / 1600.0 + 1.0 / 3600.0)
        assertEquals(expected, cursor.s, 1e-9)
    }
}
