package org.imunav.core

import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellPositioner
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.InMemoryCellTowerDb
import org.imunav.core.cells.Radio
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic geometry and malformed measurements must not manufacture confidence or NaN fixes. */
class CellPositionerNumericsTest {
    private fun key(cid: Long, radio: Radio = Radio.LTE) = CellKey(radio, 255, 1, 1234, cid)
    private fun tower(cid: Long) = CellTower(key(cid), 50.0, 30.0, 2000.0, 10)

    @Test
    fun malformedTowerCannotPoisonAValidNeighbour() {
        val valid = tower(1)
        val other = tower(2)
        val invalid = listOf(
            other.copy(lat = Double.NaN), other.copy(lon = Double.NaN),
            other.copy(lat = Double.POSITIVE_INFINITY), other.copy(lon = Double.NEGATIVE_INFINITY),
            other.copy(lat = 91.0), other.copy(lon = 181.0), other.copy(lat = 0.0, lon = 0.0),
            other.copy(rangeM = Double.NaN), other.copy(rangeM = Double.POSITIVE_INFINITY), other.copy(rangeM = Double.NEGATIVE_INFINITY),
        )
        for (bad in invalid) {
            val fix = assertNotNull(CellPositioner.locate(listOf(CellObservation(bad.key), CellObservation(valid.key)), InMemoryCellTowerDb(listOf(bad, valid))))
            assertEquals(valid.lat, fix.lat)
            assertEquals(valid.lon, fix.lon)
            assertEquals(valid.rangeM, fix.accuracyM, 1e-9)
            assertEquals(1, fix.towersUsed)
            assertEquals(2, fix.towersSeen)
            assertEquals(listOf(valid.key), fix.contributions.map { it.observation.key })
        }
    }

    @Test
    fun noUsableGeometryProducesNoFix() {
        val invalid = tower(1).copy(rangeM = Double.NaN)
        assertNull(CellPositioner.locate(listOf(CellObservation(invalid.key)), InMemoryCellTowerDb(listOf(invalid))))
    }

    @Test
    fun repeatedCellCannotImproveAccuracyOrMoveCentroid() {
        val towers = listOf(tower(1), tower(2).copy(lon = 30.01))
        val observations = towers.map { CellObservation(it.key, -90) }
        val db = InMemoryCellTowerDb(towers)
        val original = assertNotNull(CellPositioner.locate(observations, db))
        val repeated = assertNotNull(CellPositioner.locate(observations + List(100) { observations.first() }, db))
        assertEquals(original.lat, repeated.lat)
        assertEquals(original.lon, repeated.lon)
        assertEquals(original.accuracyM, repeated.accuracyM)
        assertEquals(original.contributions, repeated.contributions)
        assertEquals(2, repeated.towersUsed)
        assertEquals(102, repeated.towersSeen, "raw observation count stays diagnostic")
    }

    @Test
    fun firstCopyOfAnIdentityIsPreserved() {
        val tower = tower(1)
        val newest = CellObservation(tower.key, -90, serving = true, timingAdvance = 5)
        val stale = newest.copy(dbm = -60, timingAdvance = 0)
        val fix = assertNotNull(CellPositioner.locate(listOf(newest, stale), InMemoryCellTowerDb(listOf(tower))))
        assertEquals(newest, fix.contributions.single().observation)
        assertEquals(6 * 78.12, fix.accuracyM, 1e-9)
    }

    @Test
    fun invalidTimingAdvanceDoesNotTightenAccuracy() {
        val tower = tower(1)
        for (invalid in listOf(null, -1, Int.MIN_VALUE, 1283, Int.MAX_VALUE)) {
            val observation = CellObservation(tower.key, serving = true, timingAdvance = invalid)
            val fix = assertNotNull(CellPositioner.locate(listOf(observation), InMemoryCellTowerDb(listOf(tower))))
            assertEquals(2000.0, fix.accuracyM, 1e-9, "timingAdvance=$invalid")
        }
    }

    @Test
    fun timingAdvanceRequiresLteServingCellAndKeepsValidBounds() {
        for (radio in Radio.entries) {
            for (serving in listOf(false, true)) {
                val tower = tower(1).copy(key = key(1, radio))
                val observation = CellObservation(tower.key, serving = serving, timingAdvance = 5)
                val fix = assertNotNull(CellPositioner.locate(listOf(observation), InMemoryCellTowerDb(listOf(tower))))
                assertEquals(if (radio == Radio.LTE && serving) 6 * 78.12 else 2000.0, fix.accuracyM, 1e-9)
            }
        }
        for ((advance, expected) in listOf(0 to 150.0, 1282 to 2000.0)) {
            val tower = tower(1)
            val observation = CellObservation(tower.key, serving = true, timingAdvance = advance)
            val fix = assertNotNull(CellPositioner.locate(listOf(observation), InMemoryCellTowerDb(listOf(tower))))
            assertEquals(expected, fix.accuracyM, 1e-9)
        }
    }

    @Test
    fun mutuallyConflictingTowersAreNotRestoredAfterRejection() {
        val towers = listOf(tower(1), tower(2).copy(lat = 49.0, lon = 31.0), tower(3).copy(lat = 51.0, lon = 32.0))
        // Component medians are (50, 31); all three towers are more than 25 km from that point.
        assertNull(CellPositioner.locate(towers.map { CellObservation(it.key) }, InMemoryCellTowerDb(towers)))
    }

    @Test
    fun equalWeightTriangleMatchesAnalyticCentroidRegardlessOfOrder() {
        val towers = listOf(tower(1).copy(lon = 29.999), tower(2).copy(lon = 30.001), tower(3).copy(lat = 50.001))
        val observations = towers.map { CellObservation(it.key) }
        val db = InMemoryCellTowerDb(towers)
        for (order in listOf(observations, observations.reversed())) {
            val fix = assertNotNull(CellPositioner.locate(order, db))
            assertEquals(50.0 + 0.001 / 3, fix.lat, 1e-12)
            assertEquals(30.0, fix.lon, 1e-12)
            assertEquals(2000.0 / sqrt(3.0), fix.accuracyM, 1e-9)
            assertTrue(fix.lat.isFinite() && fix.lon.isFinite() && fix.accuracyM.isFinite())
        }
    }
}
