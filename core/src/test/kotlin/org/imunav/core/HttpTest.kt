package org.imunav.core

import com.sun.net.httpserver.HttpServer
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellSyncClient
import org.imunav.core.cells.Radio
import org.imunav.core.cells.ResumableHttpInputStream
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests the OkHttp helpers against a tiny local HTTP server (the JDK's built-in one). */
class HttpTest {
    private lateinit var server: HttpServer
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) // port 0 = any free port
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun getTextReturnsBodyAndSendsUserAgent() {
        var userAgent: String? = null
        server.createContext("/hello") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            val body = "hi".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertEquals("hi", Http.getText("$baseUrl/hello"))
        assertEquals(Http.USER_AGENT, userAgent)
    }

    @Test
    fun getTextThrowsHttpExceptionWithServerMessage() {
        server.createContext("/fail") { exchange ->
            val body = "no such route".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val e = assertFailsWith<HttpException> { Http.getText("$baseUrl/fail") }
        assertEquals(404, e.code)
        assertTrue("no such route" in e.message.orEmpty())
    }

    @Test
    fun syncClientReadsRemovalKeysAndIncrementalQuery() {
        var query: String? = null
        server.createContext("/v1/cells/removals.csv") { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.responseHeaders.add("X-Cell-Sync-Time", "456")
            val body = "radio,mcc,mnc,area,cid\nLTE,255,1,1864,99\nNR,255,2,7,12345678901\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val keys = ArrayList<CellKey>()
        val download = CellSyncClient(baseUrl).downloadRemovals(listOf(255), 123) { keys += it }
        assertEquals(2, download.count)
        assertEquals(456, download.serverEpochS)
        assertEquals("mcc=255&since=123", query)
        assertEquals(CellKey(Radio.LTE, 255, 1, 1864, 99), keys[0])
        assertEquals(CellKey(Radio.NR, 255, 2, 7, 12345678901), keys[1])
    }

    @Test
    fun resumableStreamContinuesAfterConnectionDrop() {
        val data = Random(42).nextBytes(300_000)
        val requests = AtomicInteger()
        server.createContext("/big") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (requests.incrementAndGet() == 1) {
                // First request: promise everything, send only a third, then drop the connection.
                check(range == null)
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.write(data, 0, data.size / 3)
                exchange.responseBody.flush()
                exchange.close()
            } else {
                // Resume: "bytes=<from>-" → answer 206 with the rest.
                val from = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
                exchange.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                exchange.sendResponseHeaders(206, (data.size - from).toLong())
                exchange.responseBody.use { it.write(data, from, data.size - from) }
            }
        }
        val received = ResumableHttpInputStream("$baseUrl/big", maxRetries = 3).use { it.readBytes() }
        assertContentEquals(data, received)
        assertEquals(2, requests.get(), "one drop → exactly one resume request")
    }

    @Test
    fun resumableStreamGivesUpWhenServerIgnoresRange() {
        val data = ByteArray(100_000) { it.toByte() }
        val requests = AtomicInteger()
        server.createContext("/norange") { exchange ->
            exchange.sendResponseHeaders(200, data.size.toLong())
            if (requests.incrementAndGet() == 1) {
                exchange.responseBody.write(data, 0, 1000)
                exchange.responseBody.flush()
                exchange.close()
            } else {
                exchange.responseBody.use { it.write(data) } // 200 again = cannot resume
            }
        }
        assertFailsWith<ResumableHttpInputStream.NotResumableException> {
            ResumableHttpInputStream("$baseUrl/norange", maxRetries = 3).use { it.readBytes() }
        }
    }
}
