package org.imunav.app.cells

import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellPositioner
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.InMemoryCellTowerDb
import org.imunav.core.cells.Radio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for modem signal conversion before observations reach the positioner. */
class CellSignalTest {
    @Test
    fun preservesNegativeSignalPower() {
        for (dbm in listOf(-140, -113, -110, -95, -80, -51, -43)) {
            assertEquals(dbm, cellSignalDbm(dbm))
        }
    }

    @Test
    fun unavailableSignalIsMissing() {
        assertNull(cellSignalDbm(Int.MAX_VALUE))
    }

    @Test
    fun strongerModemSignalPullsPositionTowardsItsTower() {
        val west = CellTower(CellKey(Radio.LTE, 255, 1, 1234, 1), 50.45, 30.50, 1000.0, 10)
        val east = CellTower(CellKey(Radio.LTE, 255, 1, 1234, 2), 50.45, 30.54, 1000.0, 10)
        val database = InMemoryCellTowerDb(listOf(west, east))
        val strongWest = requireNotNull(
            CellPositioner.locate(
                listOf(CellObservation(west.key, cellSignalDbm(-80)), CellObservation(east.key, cellSignalDbm(-110))),
                database,
            ),
        )
        val strongEast = requireNotNull(
            CellPositioner.locate(
                listOf(CellObservation(west.key, cellSignalDbm(-110)), CellObservation(east.key, cellSignalDbm(-80))),
                database,
            ),
        )
        val midpointLon = (west.lon + east.lon) / 2
        assertTrue(strongWest.lon < midpointLon)
        assertTrue(strongEast.lon > midpointLon)
    }
}
