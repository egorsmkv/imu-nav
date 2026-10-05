package org.imunav.app

import org.imunav.app.cells.parseMccs
import org.imunav.app.maps.MapPackInfo
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldwideDefaultsTest {
    @Test
    fun regionalPackCoverageSelectsOnlineMapOutsideItsBounds() {
        val regional = MapPackInfo("Region", "", 0, 14, listOf(40.0, 50.0, 20.0, 30.0))
        assertTrue(regional.covers(GeoPoint(45.0, 25.0)))
        assertFalse(regional.covers(GeoPoint(35.0, 25.0)))
        assertTrue(MapPackInfo("Legacy", "", 0, 14).covers(GeoPoint(35.0, 25.0)))
    }

    @Test
    fun towerCountrySelectionNeverFallsBackToUkraine() {
        assertEquals(emptySet<Int>(), parseMccs(""))
        assertEquals(setOf(310, 311), parseMccs("310, 311"))
        assertEquals(emptySet<Int>(), parseMccs("310, unknown"))
    }
}
