package org.imunav.core.bookmarks

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class BookmarksTest {
    private val destination = BookmarkPoint(GeoPoint(50.45, 30.52), "Київ, Хрещатик")

    @Test
    fun namesAndCoordinatesMustBeUsable() {
        for (name in listOf("", " ", " home")) {
            assertFailsWith<IllegalArgumentException> { SavedPlace("id", name, destination) }
        }
        for (point in listOf(GeoPoint(Double.NaN, 30.0), GeoPoint(50.0, Double.POSITIVE_INFINITY), GeoPoint(91.0, 30.0), GeoPoint(50.0, -181.0))) {
            assertFailsWith<IllegalArgumentException> { BookmarkPoint(point) }
        }
        assertEquals("Home", SavedPlace("id", "Office", destination).renamed(" Home ").name)
    }

    @Test
    fun routesKeepAutomaticAndFixedOriginsDistinct() {
        val automatic = SavedRoute("auto", "Home", BookmarkOrigin.Automatic, destination, TravelMode.CAR)
        val fixed = automatic.copy(id = "fixed", origin = BookmarkOrigin.Fixed(destination), mode = TravelMode.FOOT)
        assertEquals(BookmarkOrigin.Automatic, automatic.origin)
        assertEquals(destination, (fixed.origin as BookmarkOrigin.Fixed).endpoint)
        assertEquals(TravelMode.FOOT, (fixed.renamed("Walk") as SavedRoute).mode)
        assertNotEquals(automatic, fixed)
    }

    @Test
    fun renamingOrRemovingAPlaceDoesNotChangeRoutes() {
        val place = SavedPlace("place", "Home", destination)
        val route = SavedRoute("route", "Commute", BookmarkOrigin.Fixed(place.endpoint), place.endpoint, TravelMode.CAR)
        val original = route.copy()
        val changed = listOf(place, route).map { if (it.id == place.id) it.renamed("Office") else it }.filterNot { it.id == place.id }
        assertEquals(listOf(original), changed)
    }

    @Test
    fun filterMatchesAllWordsAcrossNameAndAddressWithStableOrdering() {
        val first = SavedPlace("1", "Home", destination)
        val second = first.copy(id = "2")
        val third = SavedPlace("3", "Airport", BookmarkPoint(GeoPoint(50.0, 30.0), "Бориспіль"))
        assertEquals(listOf(first, second), listOf(second, third, first).matching("КИЇВ home"))
        assertEquals(listOf(third, first, second), listOf(second, first, third).matching(""))
        assertTrue(listOf(first).matching("missing").isEmpty())
    }
}
