package org.blinddriver.core

import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellLearning
import org.blinddriver.core.cells.CellObservation
import org.blinddriver.core.cells.CellPositioner
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.InMemoryCellTowerDb
import org.blinddriver.core.cells.OpenCellIdCsv
import org.blinddriver.core.cells.Radio
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.gnss.FixSource
import org.blinddriver.core.gnss.PositioningHub
import org.blinddriver.core.gnss.RawFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellsTest {
    private fun key(cid: Long) = CellKey(Radio.LTE, 255, 1, 1234, cid)

    @Test
    fun parsesOpenCellIdLine() {
        val t = assertNotNull(OpenCellIdCsv.parse("LTE,255,1,1234,56789012,0,30.5234,50.4501,850,12,1,1500000000,1600000000,0"))
        assertEquals(key(56789012), t.key)
        assertEquals(50.4501, t.lat, 1e-9)
        assertEquals(30.5234, t.lon, 1e-9)
        assertEquals(850.0, t.rangeM)
        assertNull(OpenCellIdCsv.parse("radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"))
        assertNull(OpenCellIdCsv.parse("WIMAX,255,1,1,1,0,30,50,1,1,1,1,1,0"))
    }

    @Test
    fun weightedCentroidLeansTowardsStrongServingCell() {
        val a = CellTower(key(1), 50.45, 30.50, 1000.0, 10)
        val b = CellTower(key(2), 50.45, 30.54, 1000.0, 10)
        val db = InMemoryCellTowerDb(listOf(a, b))
        val fix = assertNotNull(CellPositioner.locate(listOf(CellObservation(key(1), -80, serving = true), CellObservation(key(2), -110)), db))
        assertTrue(Geo.distance(fix.lat, fix.lon, a.lat, a.lon) < Geo.distance(fix.lat, fix.lon, b.lat, b.lon))
        assertEquals(2, fix.towersUsed)
        assertTrue(fix.accuracyM in 150.0..5000.0)
    }

    @Test
    fun dropsFarOutlierAndIgnoresUnknownCells() {
        val towers = listOf(
            CellTower(key(1), 50.45, 30.50, 800.0, 5),
            CellTower(key(2), 50.46, 30.51, 800.0, 5),
            CellTower(key(3), 50.44, 30.52, 800.0, 5),
            CellTower(key(4), 48.0, 24.0, 800.0, 1), // wrong entry ~450 km away
        )
        val obs = (1L..5L).map { CellObservation(key(it), -90) }
        val fix = assertNotNull(CellPositioner.locate(obs, InMemoryCellTowerDb(towers)))
        assertEquals(3, fix.towersUsed)
        assertEquals(5, fix.towersSeen)
        assertTrue(Geo.distance(fix.lat, fix.lon, 50.45, 30.51) < 2000)
        assertNull(CellPositioner.locate(listOf(CellObservation(key(99))), InMemoryCellTowerDb(towers)))
    }

    @Test
    fun learningConvergesToCentroid() {
        var t: CellTower? = null
        for (i in 0 until 100) t = CellLearning.update(t, key(7), 50.45 + (i % 2) * 0.002, 30.52, 10.0)
        assertEquals(50.451, t!!.lat, 1e-4)
        assertEquals(100, t.samples)
        assertTrue(t.rangeM > 100)
    }

    @Test
    fun cellFixStandsInForMissingNetworkFix() {
        val hub = PositioningHub(wallClock = { 0L })
        hub.onFix(RawFix(FixSource.CELL, 0, 1000, 50.45, 30.52, accuracyM = 600f))
        assertEquals(FixSource.CELL, hub.lastNet?.source)
        hub.onFix(RawFix(FixSource.NET, 0, 2000, 50.45, 30.52, accuracyM = 40f))
        hub.onFix(RawFix(FixSource.CELL, 0, 3000, 50.45, 30.52, accuracyM = 600f))
        assertEquals(FixSource.NET, hub.lastNet?.source, "fresh, more accurate platform fix is kept")
        hub.onFix(RawFix(FixSource.CELL, 0, 20_000, 50.45, 30.52, accuracyM = 600f))
        assertEquals(FixSource.CELL, hub.lastNet?.source, "stale platform fix is replaced")
    }
}
