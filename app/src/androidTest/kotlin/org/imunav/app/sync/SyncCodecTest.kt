package org.imunav.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.imunav.core.sync.SyncEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's real JSON implementation, including Unicode and nullable endpoints. */
@RunWith(AndroidJUnit4::class)
class SyncCodecTest {
    @Test
    fun placesAndRouteRecipesRoundTripWithoutFreezingAutomaticStarts() {
        val point = BookmarkPoint(GeoPoint(50.45, 30.52), "Київ")
        val bookmarks = listOf(
            SavedPlace("home", "Дім", point),
            SavedRoute("auto", "Work", BookmarkOrigin.Automatic, point, TravelMode.CAR),
            SavedRoute("fixed", "Walk", BookmarkOrigin.Fixed(point.copy(label = null)), point, TravelMode.FOOT),
        )
        bookmarks.forEach { assertEquals(it, SyncCodec.bookmark(it.id, SyncCodec.bookmark(it))) }
    }

    @Test
    fun canonicalObjectsAndDeletionMarkersSurviveEncoding() {
        assertEquals(SyncCodec.canonical(JSONObject("{\"a\":1,\"b\":false}")), SyncCodec.canonical(JSONObject("{\"b\":false,\"a\":1}")))
        val entries = listOf(SyncEntry("bookmark", "removed", 2, null), SyncEntry("setting", "voice", 1, "false"))
        assertEquals(entries, SyncCodec.entries(SyncCodec.entries(entries)))
        assertNotEquals(SyncDisk.profile("https://one.example", 1), SyncDisk.profile("https://two.example", 1))
        assertNotEquals(SyncDisk.profile("https://one.example", 1), SyncDisk.profile("https://one.example", 2))
    }

    @Test
    fun invalidCoordinatesAndUnknownKindsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SyncCodec.point(JSONObject("{\"lat\":91,\"lon\":30}")) }
        assertThrows(IllegalStateException::class.java) { SyncCodec.bookmark("id", "{\"type\":\"unknown\"}") }
    }
}
