package org.imunav.app.maps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** The loopback tile server answers only its one path and survives a build without the bundled map. */
@RunWith(RobolectricTestRunner::class)
class BundledMapServerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val log = CopyOnWriteArrayList<String>()

    private fun get(server: BundledMapServer, path: String, range: String? = null): String {
        val url = URI(server.tileUrl)
        return Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 5_000
            val headers = "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (range?.let { "Range: $it\r\n" } ?: "") + "\r\n"
            socket.getOutputStream().apply {
                write(headers.toByteArray(Charsets.US_ASCII))
                flush()
            }
            socket.getInputStream().bufferedReader(Charsets.US_ASCII).readText()
        }
    }

    @Test
    fun unknownPathsAreNotFound() {
        BundledMapServer(context) { log += it }.use { server ->
            assertTrue(server.tileUrl.matches(Regex("http://127\\.0\\.0\\.1:\\d+/tiles\\.pmtiles")))
            val response = get(server, "/other")
            assertTrue(response, response.startsWith("HTTP/1.1 404 Not Found"))
            assertTrue(response.contains("Content-Length: 0"))
        }
    }

    @Test
    fun missingBundledAssetClosesTheConnectionAndIsLogged() {
        BundledMapServer(context) { log += it }.use { server ->
            assertEquals("", get(server, "/tiles.pmtiles", range = "bytes=0-15"))
            val deadline = System.currentTimeMillis() + 5_000
            while (log.none { it.startsWith("bundled_map_range_failed") }) {
                check(System.currentTimeMillis() < deadline) { "no failure logged: $log" }
                Thread.sleep(10)
            }
        }
    }
}
