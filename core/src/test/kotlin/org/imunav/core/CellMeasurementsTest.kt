package org.imunav.core

import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellMeasurement
import org.imunav.core.cells.CellMeasurementTracker
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellPositioner
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.InMemoryCellTowerDb
import org.imunav.core.cells.Radio
import org.imunav.core.cells.cellLearningKeys
import org.imunav.core.cells.isFreshCellMeasurement
import org.imunav.core.gnss.FixSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cached modem responses must not masquerade as independent, newly measured positions. */
class CellMeasurementsTest {
    private fun measurement(cid: Long, elapsedMs: Long, dbm: Int = -90) = CellMeasurement(CellObservation(CellKey(Radio.LTE, 255, 1, 1234, cid), dbm), elapsedMs)

    /** Use the real positioner so freshness checks see its actual contributing cells. */
    private fun locate(measurements: List<CellMeasurement>) = assertNotNull(
        CellPositioner.locate(
            measurements.map { it.observation },
            InMemoryCellTowerDb(measurements.map { CellTower(it.observation.key, 50.45, 30.5, 1000.0, 10) }),
        ),
    )

    @Test
    fun acceptsAgeBoundaryButRejectsInvalidFutureAndExpiredTimes() {
        assertTrue(isFreshCellMeasurement(20_000, 20_000))
        assertTrue(isFreshCellMeasurement(10_000, 20_000))
        for (elapsedMs in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 20_001L, 9_999L)) {
            assertFalse(isFreshCellMeasurement(elapsedMs, 20_000), "elapsedMs=$elapsedMs")
        }
    }

    @Test
    fun freshNeighbourDoesNotKeepStaleServingCell() {
        val stale = measurement(1, 9_999).let { it.copy(observation = it.observation.copy(serving = true)) }
        val recent = measurement(2, 19_000)
        val tracker = CellMeasurementTracker()
        assertEquals(listOf(recent), tracker.update(1, listOf(stale, recent), 20_000))
    }

    @Test
    fun anotherSimCannotRefreshCachedMeasurements() {
        val tracker = CellMeasurementTracker()
        tracker.update(1, listOf(measurement(1, 10_000)), 10_000)
        val recent = measurement(2, 20_001)
        assertEquals(listOf(recent), tracker.update(2, listOf(recent), 20_001))
        assertTrue(tracker.update(1, listOf(measurement(1, 10_000)), 30_002).isEmpty())
    }

    @Test
    fun duplicateCellsUseNewestMeasurementRegardlessOfSimCallbackOrder() {
        val tracker = CellMeasurementTracker()
        val newer = measurement(1, 19_000, -80)
        tracker.update(2, listOf(newer), 19_000)
        assertEquals(listOf(newer), tracker.update(1, listOf(measurement(1, 18_000, -110)), 20_000))
        assertEquals(listOf(newer), tracker.update(1, emptyList(), 20_001))
    }

    @Test
    fun fixesKeepMeasurementTimeAndCachedCallbacksDoNotEmitAgain() {
        val tracker = CellMeasurementTracker()
        val cells = listOf(measurement(1, 18_000), measurement(2, 19_000))
        val firstScan = tracker.update(1, cells, 20_000)
        val fix = locate(firstScan)
        val raw = assertNotNull(tracker.positionFix(fix, firstScan, 20_000, 1_000_000))
        assertEquals(FixSource.CELL, raw.source)
        assertEquals(18_000L, raw.elapsedMs)
        assertEquals(998_000L, raw.timeMs)

        val repeatScan = tracker.update(1, cells, 21_000)
        assertNull(tracker.positionFix(fix, repeatScan, 21_000, 1_001_000))
        val newerNeighbour = tracker.update(1, listOf(cells[0], measurement(2, 21_000)), 21_000)
        assertNull(tracker.positionFix(locate(newerNeighbour), newerNeighbour, 21_000, 1_001_000))
        val refreshed = tracker.update(1, listOf(measurement(1, 22_000), measurement(2, 22_000)), 22_000)
        assertEquals(22_000L, assertNotNull(tracker.positionFix(locate(refreshed), refreshed, 22_000, 1_002_000)).elapsedMs)
    }

    @Test
    fun outOfOrderFixCannotMoveMeasurementClockBackwards() {
        val tracker = CellMeasurementTracker()
        val current = listOf(measurement(1, 20_000))
        assertNotNull(tracker.positionFix(locate(current), current, 20_000, 1_000_000))
        val older = listOf(measurement(1, 19_000))
        assertNull(tracker.positionFix(locate(older), older, 21_000, 1_001_000))
    }

    @Test
    fun expiringNeighbourDoesNotTurnRemainingCachedCellIntoNewFix() {
        val tracker = CellMeasurementTracker()
        val cells = listOf(measurement(1, 10_000), measurement(2, 15_000))
        val first = tracker.update(1, cells, 16_000)
        assertNotNull(tracker.positionFix(locate(first), first, 16_000, 1_000_000))
        val remaining = tracker.update(1, cells, 21_000)
        assertEquals(listOf(cells[1]), remaining)
        assertNull(tracker.positionFix(locate(remaining), remaining, 21_000, 1_005_000))
    }

    @Test
    fun onlyContributingCellsDetermineFixTime() {
        val tracker = CellMeasurementTracker()
        val known = listOf(measurement(1, 19_000))
        val withUnknown = known + measurement(2, 10_000)
        assertEquals(19_000L, assertNotNull(tracker.positionFix(locate(known), withUnknown, 20_000, 1_000_000)).elapsedMs)
    }

    @Test
    fun slowDatabaseWorkCannotPublishExpiredMeasurements() {
        val tracker = CellMeasurementTracker()
        val cells = listOf(measurement(1, 10_000))
        val fix = locate(cells)
        assertNull(tracker.positionFix(fix, cells, 20_001, 1_000_000))
        assertNull(tracker.positionFix(fix, emptyList(), 20_000, 1_000_000))
        assertNull(tracker.positionFix(fix.copy(contributions = emptyList()), cells, 20_000, 1_000_000))
    }

    @Test
    fun learningRechecksIndividualMeasurementAndGpsAgeAfterScanningStops() {
        val recent = measurement(1, 19_000)
        val cells = listOf(recent, measurement(2, 9_999))
        assertEquals(listOf(recent.observation.key), cellLearningKeys(cells, 20_000, 20_000))
        assertTrue(cellLearningKeys(cells, 30_001, 30_001).isEmpty())
        assertTrue(cellLearningKeys(cells, 9_999, 20_000).isEmpty())
        assertTrue(cellLearningKeys(cells, 20_001, 20_000).isEmpty())
    }
}
