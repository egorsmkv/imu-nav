package org.imunav.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.imunav.app.bookmarks.BookmarkDatabase
import org.imunav.app.net.ProxySettings
import org.imunav.app.search.RecentSearchCodec
import org.imunav.app.sync.SyncCodec
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.geo.GeoPoint
import org.imunav.core.net.ProxyConfig
import org.imunav.core.net.ProxyMode
import org.imunav.core.route.RoadPreference
import org.imunav.core.route.TravelMode
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import org.imunav.core.sync.SyncEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Saved data round-trips unchanged, and damaged data is skipped instead of crashing the app. */
@RunWith(RobolectricTestRunner::class)
class StorageCodecsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val home = SavedPlace("p1", "Home", BookmarkPoint(GeoPoint(50.45, 30.52), "Khreshchatyk 1"))
    private val work = SavedRoute(
        "r1",
        "To work",
        BookmarkOrigin.Fixed(BookmarkPoint(GeoPoint(50.40, 30.60), null)),
        BookmarkPoint(GeoPoint(50.50, 30.40), "Office"),
        TravelMode.FOOT,
    )
    private val anywhere = SavedRoute("r2", "Anywhere", BookmarkOrigin.Automatic, BookmarkPoint(GeoPoint(49.84, 24.03)), TravelMode.CAR, RoadPreference.BALANCED)

    @Test
    fun olderRouteBookmarksMigrateWithoutLosingEndpoints() {
        val name = "bookmarks-v1.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE bookmarks (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, label TEXT, automatic INTEGER, start_lat REAL, start_lon REAL, start_label TEXT, mode TEXT)",
            )
            db.execSQL("INSERT INTO bookmarks (id, name, kind, lat, lon, automatic, mode) VALUES ('old', 'Old route', 'route', 1, 2, 1, 'CAR')")
            db.version = 1
        }
        BookmarkDatabase(context, name).use { db ->
            val old = db.load().single() as SavedRoute
            assertEquals(GeoPoint(1.0, 2.0), old.destination.point)
            assertEquals(RoadPreference.MAIN_ROADS, old.roadPreference)
            db.save(old.copy(roadPreference = RoadPreference.BALANCED))
        }
        BookmarkDatabase(context, name).use { db -> assertEquals(RoadPreference.BALANCED, (db.load().single() as SavedRoute).roadPreference) }
        val legacyWire = JSONObject(SyncCodec.bookmark(anywhere)).apply { remove("roadPreference") }
        assertEquals(RoadPreference.MAIN_ROADS, (SyncCodec.bookmark("legacy", legacyWire.toString()) as SavedRoute).roadPreference)
    }

    @Test
    fun roadPreferenceIsRememberedAcrossAppStarts() {
        val preferences = NavigationPreferences(context)
        preferences.setRoadPreference(RoadPreference.BALANCED)
        assertEquals(RoadPreference.BALANCED, NavigationPreferences(context).roadPreference.value)
        preferences.setRoadPreference(RoadPreference.MAIN_ROADS)
    }

    @Test
    fun bookmarksSurviveReopeningAndReplacingTheDatabase() {
        context.deleteDatabase("bookmarks-test.db")
        BookmarkDatabase(context, "bookmarks-test.db").use { db ->
            db.save(home)
            db.save(work)
            db.save(anywhere)
            db.save(home.copy(name = "Home sweet home"))
        }
        BookmarkDatabase(context, "bookmarks-test.db").use { db ->
            assertEquals(setOf(home.copy(name = "Home sweet home"), work, anywhere), db.load().toSet())
            db.delete("r2")
            assertEquals(setOf("p1", "r1"), db.load().map { it.id }.toSet())
            db.replaceAll(listOf(anywhere))
            assertEquals(listOf(anywhere), db.load())
        }
    }

    @Test
    fun recentSearchesSalvageValidRowsFromDamagedHistory() {
        val result = SearchResult(ResultKind.ADDRESS, "Khreshchatyk, 1", "Kyiv", GeoPoint(50.45, 30.52), distanceM = 120.0, source = "offline")
        val decoded = RecentSearchCodec.decode(RecentSearchCodec.encode(listOf(result)))
        assertEquals(1, decoded.size)
        assertEquals(result.copy(distanceM = null, source = "recent"), decoded.single())

        assertTrue(RecentSearchCodec.decode(null).isEmpty())
        assertTrue(RecentSearchCodec.decode("not json").isEmpty())
        val damaged = JSONArray()
            .put(JSONObject.NULL)
            .put(JSONObject().put("k", "PLANET").put("t", "x").put("lat", 1.0).put("lon", 2.0))
            .put(JSONObject().put("k", "PLACE").put("t", "missing coordinates"))
            .put(JSONArray(RecentSearchCodec.encode(listOf(result))).get(0))
        assertEquals(listOf("Khreshchatyk, 1"), RecentSearchCodec.decode(damaged.toString()).map { it.title })
    }

    @Test
    fun syncWireFormatIsCanonicalAndRejectsInvalidData() {
        for (bookmark in listOf(home, work, anywhere)) {
            assertEquals(bookmark, SyncCodec.bookmark(bookmark.id, SyncCodec.bookmark(bookmark)))
        }
        // Key order and serializer quirks must not make equal values look different.
        assertEquals(SyncCodec.canonical(JSONObject("{\"b\":1,\"a\":[true,\"x\"]}")), SyncCodec.canonical(JSONObject("{\"a\":[true,\"x\"],\"b\":1}")))
        assertThrows(IllegalStateException::class.java) { SyncCodec.bookmark("x", "{\"type\":\"folder\",\"name\":\"n\"}") }
        assertThrows(IllegalArgumentException::class.java) { SyncCodec.point(JSONObject().put("lat", 95.0).put("lon", 0.0)) }

        val entries = listOf(SyncEntry("setting", "theme", 3, "\"dark\""), SyncEntry("bookmark", "p1", 1, null))
        assertEquals(entries, SyncCodec.entries(SyncCodec.entries(entries)))
        assertThrows(IllegalArgumentException::class.java) {
            SyncCodec.entries(JSONArray().put(JSONObject().put("kind", "password").put("key", "k").put("revision", 1).put("value", 1)))
        }
    }

    @Test
    fun proxySettingsAreRestoredAndCorruptValuesFallBackToSystem() {
        val prefs = context.getSharedPreferences("proxy", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(ProxyConfig.SYSTEM, ProxySettings(context).config.value)

        val socks = checkNotNull(ProxyConfig.parse(ProxyMode.SOCKS, "127.0.0.1", "1080", "user", "secret"))
        ProxySettings(context).save(socks)
        assertEquals(socks, ProxySettings(context).config.value)

        prefs.edit().putString("mode", "HTTP").putString("host", "").commit()
        assertEquals("a proxy without a host cannot be used", ProxyConfig.SYSTEM, ProxySettings(context).config.value)
        ProxySettings(context).save(ProxyConfig.SYSTEM)
    }
}
