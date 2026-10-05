package org.imunav.app

import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks that pasted map coordinates are accepted only when both values are usable. */
class MapStartPrefsTest {
    @Test
    fun acceptsCommonCoordinateSeparatorsAndBoundaryValues() {
        assertEquals(GeoPoint(50.45, 30.52), MapStartPrefs.parse("50.45, 30.52"))
        assertEquals(GeoPoint(50.45, 30.52), MapStartPrefs.parse(" 50.45;30.52 "))
        assertEquals(GeoPoint(50.45, 30.52), MapStartPrefs.parse("50.45 30.52"))
        assertEquals(GeoPoint(-90.0, 180.0), MapStartPrefs.parse("-90, 180"))
    }

    @Test
    fun rejectsMissingInvalidAndOutOfRangeCoordinates() {
        listOf("", "50.45", "50.45, 30.52, 10", "north, 30.52", "50.45, east", "90.01, 30", "50, -180.01", "NaN, 30", "50, Infinity")
            .forEach { assertNull("Expected invalid coordinates: $it", MapStartPrefs.parse(it)) }
    }
}
