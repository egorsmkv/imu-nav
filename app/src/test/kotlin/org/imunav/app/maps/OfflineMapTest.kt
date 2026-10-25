package org.imunav.app.maps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.imunav.app.R
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A map pack is installed from a file or URL, drawn only inside its bounds and removed again. */
@RunWith(RobolectricTestRunner::class)
class OfflineMapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log = ArrayList<String>()
    private lateinit var map: OfflineMap

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${map.status.value} ${log.takeLast(3)}" }
            ShadowLooper.idleMainLooper(STEP_MS, TimeUnit.MILLISECONDS)
            Thread.sleep(STEP_MS)
        }
    }

    /** The smallest pack the installer accepts: tiles, both styles and a `pack.json` describing Kyiv. */
    private fun pack(kind: String = "map"): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            val style = """{"version":8,"sources":{"tiles":{"type":"vector","url":"pmtiles://{PACK_URI}/tiles.pmtiles"}},"layers":[]}"""
            entry("tiles.pmtiles", "PMTiles")
            entry("style-light.json", style)
            entry("style-dark.json", style.replace("\"layers\":[]", "\"layers\":[],\"name\":\"dark\""))
            entry("pack.json", """{"kind":"$kind","name":"Kyiv","builtAt":"2026-10-01","sizeBytes":7,"maxzoom":14,"bounds":[50.2,50.6,30.2,30.8]}""")
            entry("fonts/readme.txt", "glyphs")
        }
    }.toByteArray()

    @Before
    fun setUp() {
        File(context.filesDir, "map").deleteRecursively()
        context.getSharedPreferences("offline_map", Context.MODE_PRIVATE).edit().clear().commit()
        map = OfflineMap(context, scope) { log += it }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun importedPackDrawsInsideItsBoundsUntilRemoved() {
        assertNull("no pack: the online style is used", map.styleJson(dark = false))
        map.importZip { ByteArrayInputStream(pack()) }
        waitFor("install") { map.status.value.busy == null && map.status.value.pack != null }
        val status = map.status.value
        assertEquals("Kyiv", status.pack?.name)
        assertFalse(status.bundled)
        assertTrue(status.offlineInUse)
        assertEquals(context.getString(R.string.offline_map_installed, "Kyiv"), status.message)
        assertTrue(log.any { it.startsWith("offline_map_installed Kyiv") })

        val light = checkNotNull(map.styleJson(dark = false, center = GeoPoint(50.45, 30.52)))
        assertTrue(light.contains("pmtiles://file://"))
        assertFalse(light.contains("{PACK_URI}"))
        assertTrue(checkNotNull(map.styleJson(dark = true)).contains("dark"))
        assertNull("outside the pack the online map is drawn", map.styleJson(dark = false, center = GeoPoint(49.84, 24.03)))

        map.setUseOffline(false)
        assertNull(map.styleJson(dark = false))
        map.setUseOffline(true)
        map.setCorridorEnabled(false)
        assertFalse(map.status.value.corridor)
        // With corridors off nothing is downloaded along a route.
        map.saveCorridor(Route(listOf(GeoPoint(50.4, 30.5), GeoPoint(50.5, 30.6)), listOf(Step("depart", null, "", 0.0, 0.0, 0)), 100.0), dark = false)

        map.remove()
        waitFor("removal") { map.status.value.busy == null && map.status.value.pack == null }
        assertEquals(context.getString(R.string.offline_map_removed), map.status.value.message)
        val reloaded = OfflineMap(context, scope) {}
        assertFalse(reloaded.status.value.corridor)
    }

    @Test
    fun archiveWithoutMapPackIsRejectedAndNothingIsInstalled() {
        map.importZip { ByteArrayInputStream(pack(kind = "routing")) }
        waitFor("rejection") { map.status.value.message != null }
        assertEquals(context.getString(R.string.task_failed, context.getString(R.string.offline_map_not_a_pack)), map.status.value.message)
        assertNull(map.status.value.pack)
        assertFalse(File(context.filesDir, "map/staging").exists())

        map.importZip { null }
        waitFor("unreadable file") { map.status.value.busy == null && map.status.value.message?.contains("cannot open file") == true }
    }

    @Test
    fun packIsDownloadedFromAUrl() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(Buffer().write(pack())).build())
            server.start()
            val url = server.url("/kyiv-map.zip").toString()
            map.download("  $url ")
            assertEquals(url, map.status.value.packUrl)
            waitFor("download") { map.status.value.busy == null && map.status.value.pack != null }
            assertEquals("Kyiv", map.status.value.pack?.name)
        }
        map.cancel()
    }

    @Test
    fun packDescriptionsAreValidatedAndBoundsChecked() {
        val info = checkNotNull(MapPackInfo.parse("""{"kind":"map","name":"Kyiv","bounds":[50.2,50.6,30.2,30.8]}"""))
        assertEquals(14, info.maxZoom)
        assertTrue(info.covers(GeoPoint(50.45, 30.52)))
        assertFalse(info.covers(GeoPoint(49.84, 24.03)))
        assertEquals("inverted bounds are ignored", null, MapPackInfo.parse("""{"kind":"map","name":"x","bounds":[51,50,30,31]}""")?.bounds)
        assertTrue("old packs without bounds cover everything", checkNotNull(MapPackInfo.parse("""{"kind":"map","name":"x"}""")).covers(GeoPoint(0.0, 0.0)))
        assertNull(MapPackInfo.parse("not json"))
        assertNull(MapPackInfo.parse("""{"kind":"routing","name":"x"}"""))
        assertEquals(MapStyles.DARK, MapStyles.online(dark = true))
        assertEquals(MapStyles.LIGHT, MapStyles.online(dark = false))
    }
}

private const val STEP_MS = 20L
