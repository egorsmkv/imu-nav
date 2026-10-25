package org.imunav.app.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.PhotonServer
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

/** Offline results win; Photon is asked only when allowed and the pack found nothing. */
@RunWith(RobolectricTestRunner::class)
class PlaceSearchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val kyiv = GeoPoint(50.45, 30.52)
    private var offline: List<SearchResult> = emptyList()
    private var offlineFailure: Exception? = null
    private var online = true

    private fun feature(lon: Double, lat: Double, vararg properties: Pair<String, String>) = JSONObject()
        .put("geometry", JSONObject().put("coordinates", JSONArray().put(lon).put(lat)))
        .put("properties", JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } })

    private fun photonAnswer() = JSONObject().put(
        "features",
        JSONArray()
            .put(feature(30.52, 50.45, "street" to "Khreshchatyk", "housenumber" to "22", "city" to "Kyiv"))
            .put(feature(30.5, 50.4, "osm_key" to "place", "name" to "Kyiv", "county" to "Kyiv region"))
            .put(feature(30.6, 50.5, "name" to "Maidan"))
            .put(feature(30.7, 50.6)),
    ).toString()

    private fun search() = PlaceSearch(
        context,
        { _, _, _ -> offlineFailure?.let { throw it } ?: offline },
        { offline.isNotEmpty() },
        { online },
    )

    @Before
    fun start() {
        context.getSharedPreferences("search", Context.MODE_PRIVATE).edit().clear().commit()
        server.start()
    }

    @After
    fun stop() = server.close()

    @Test
    fun photonFallbackParsesAddressesPlacesAndStreets() = runBlocking {
        val search = search()
        assertTrue(search.setPhotonUrl(server.url("/").toString()))
        assertTrue(search.photonUrl.value.endsWith("/api"))
        server.enqueue(MockResponse.Builder().body(photonAnswer()).build())

        val results = search.search("Khreshchatyk 22", kyiv)
        assertEquals(listOf(ResultKind.ADDRESS, ResultKind.PLACE, ResultKind.STREET), results.map { it.kind })
        assertEquals("Khreshchatyk, 22", results[0].title)
        assertEquals("Kyiv region", results[1].subtitle)
        assertTrue(results.all { it.source == "online" && it.distanceM != null })
        val request = server.takeRequest()
        assertEquals("50.45000", request.url.queryParameter("lat"))
        assertEquals("10", request.url.queryParameter("limit"))
        assertFalse(search.hasOffline)
    }

    @Test
    fun offlineResultsOrPolicyKeepTheQueryOnThePhone() = runBlocking {
        val search = search()
        search.setPhotonUrl(server.url("/").toString())
        assertTrue("too short", search.search("K", kyiv).isEmpty())

        offline = listOf(SearchResult(ResultKind.STREET, "Khreshchatyk", "Kyiv", kyiv, 0.0, source = "offline"))
        assertEquals(offline, search.search("Khreshchatyk", kyiv))
        assertTrue(search.hasOffline)

        offline = emptyList()
        online = false
        assertTrue(search.search("Nowhere", kyiv).isEmpty())
        offlineFailure = IOException("pack closed")
        assertTrue(search.search("Nowhere", null).isEmpty())
        assertEquals("no request reached Photon", 0, server.requestCount)
    }

    @Test
    fun photonServerSettingIsValidatedAndTested() = runBlocking {
        val search = search()
        assertFalse(search.setPhotonUrl("ftp://example.com"))
        assertTrue(search.setPhotonUrl(""))
        assertEquals(PhotonServer.DEFAULT_URL, search.photonUrl.value)

        val url = server.url("/").toString()
        server.enqueue(MockResponse.Builder().body(photonAnswer()).build())
        assertEquals(3, search.testPhoton(url).getOrThrow())
        server.enqueue(MockResponse.Builder().body("<html>").build())
        assertEquals("not a Photon server", search.testPhoton(url).exceptionOrNull()?.message)
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals("Photon HTTP 503", search.testPhoton(url).exceptionOrNull()?.message)
        assertTrue(search.testPhoton("bad url?").exceptionOrNull() is IllegalArgumentException)

        // A failing server during a normal search just gives no results.
        search.setPhotonUrl(url)
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(search.search("Khreshchatyk", kyiv).isEmpty())
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertTrue(search.search("Khreshchatyk", kyiv).isEmpty())
    }

    @Test
    fun pickedResultsAreRememberedAcrossRestarts() {
        val search = search()
        val pick = SearchResult(ResultKind.PLACE, "Lviv", "", GeoPoint(49.84, 24.03), null, source = "offline")
        search.remember(pick)
        val deadline = System.currentTimeMillis() + 5_000
        while (search.recent.value.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "recent search not saved" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertEquals("Lviv", search.recent.value.single().title)
    }
}
