package org.imunav.core

import com.sun.net.httpserver.HttpServer
import okhttp3.Credentials
import okhttp3.Request
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.imunav.core.net.ProxyConfig
import org.imunav.core.net.ProxyMode
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local proxy endpoints verify routing and credential boundaries without reaching the internet. */
class ProxyTest {
    private lateinit var proxy: HttpServer
    private lateinit var origin: HttpServer
    private val originUrl get() = "http://127.0.0.1:${origin.address.port}/hello"

    @BeforeTest
    fun start() {
        proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { exchange ->
            val body = "origin".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        proxy.start()
        origin.start()
        Http.configureProxy(assertNotNull(ProxyConfig.parse(ProxyMode.DIRECT)))
    }

    @AfterTest
    fun stop() {
        Http.configureProxy(ProxyConfig.SYSTEM)
        proxy.stop(0)
        origin.stop(0)
    }

    /** Synthetic credentials used only by these local tests. */
    private fun httpConfig(username: String = "", password: String = "") = assertNotNull(
        ProxyConfig.parse(ProxyMode.HTTP, "127.0.0.1", proxy.address.port.toString(), username, password),
    )

    @Test
    fun validatesHostPortIpv6AndRedactsCredentials() {
        val config = assertNotNull(ProxyConfig.parse(ProxyMode.HTTP, " Example.ORG ", " 3128 ", "test-user", "test-password"))
        assertEquals("example.org", config.host)
        assertFalse(config.toString().contains("test-user"))
        assertFalse(config.toString().contains("test-password"))
        assertEquals("::1", assertNotNull(ProxyConfig.parse(ProxyMode.SOCKS, "[::1]", "1080")).host)
        listOf("", "https://example.org", "user@example.org", "example.org/path", "bad host").forEach {
            assertNull(ProxyConfig.parse(ProxyMode.HTTP, it, "8080"))
        }
        listOf("0", "65536", "-1", "port").forEach { assertNull(ProxyConfig.parse(ProxyMode.HTTP, "localhost", it)) }
        assertNull(ProxyConfig.parse(ProxyMode.HTTP, "localhost", "8080", password = "test-password"))
        val socks = assertNotNull(ProxyConfig.parse(ProxyMode.SOCKS, "localhost", "1080", "test-user", "test-password"))
        assertEquals("", socks.username)
        assertEquals("", socks.password)
    }

    @Test
    fun systemAndDirectModesRestoreTheirRoutingPolicy() {
        Http.configureProxy(httpConfig())
        assertEquals(Proxy.Type.HTTP, Http.client.proxy?.type())
        Http.configureProxy(assertNotNull(ProxyConfig.parse(ProxyMode.DIRECT)))
        assertEquals(Proxy.NO_PROXY, Http.client.proxy)
        Http.configureProxy(ProxyConfig.SYSTEM)
        assertNull(Http.client.proxy)
    }

    @Test
    fun directRequestsStripLeftoverProxyAuthorization() {
        origin.removeContext("/")
        val authorization = AtomicReference<String?>()
        origin.createContext("/") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Proxy-Authorization"))
            val body = "origin".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val request = Request.Builder().url(originUrl).header("Proxy-Authorization", Credentials.basic("test-user", "test-password")).build()
        Http.client.newCall(request).execute().use { assertEquals("origin", it.body.string()) }
        assertNull(authorization.get())
    }

    @Test
    fun sharedFactorySwitchesProxyAndDirectWithoutRestart() {
        val target = AtomicReference<String>()
        proxy.createContext("/") { exchange ->
            target.set(exchange.requestURI.toString())
            val body = "proxied".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val factory = Http.callFactory
        assertEquals("origin", Http.getText(originUrl))
        Http.configureProxy(httpConfig())
        factory.newCall(Request.Builder().url(originUrl).build()).execute().use { assertEquals("proxied", it.body.string()) }
        assertEquals(originUrl, target.get())
        Http.configureProxy(assertNotNull(ProxyConfig.parse(ProxyMode.DIRECT)))
        assertEquals("origin", Http.getText(originUrl))
    }

    @Test
    fun respondsToProxyChallengeWithoutLeakingCredentialsToOrigin() {
        val calls = AtomicInteger()
        val authorization = Credentials.basic("test-user", "test-password")
        proxy.createContext("/") { exchange ->
            calls.incrementAndGet()
            if (exchange.requestHeaders.getFirst("Proxy-Authorization") == authorization) {
                val body = "authenticated".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"test\"")
                exchange.sendResponseHeaders(407, -1)
                exchange.close()
            }
        }
        Http.configureProxy(httpConfig("test-user", "test-password"))
        assertEquals("authenticated", Http.getText("http://unresolvable.invalid/hello"))
        assertEquals(2, calls.get())
        origin.removeContext("/")
        val originAuthorization = AtomicReference<String?>()
        origin.createContext("/") { exchange ->
            originAuthorization.set(exchange.requestHeaders.getFirst("Proxy-Authorization"))
            exchange.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"origin\"")
            exchange.sendResponseHeaders(407, -1)
            exchange.close()
        }
        val direct = Http.client.newBuilder().proxy(Proxy.NO_PROXY).build()
        assertFailsWith<IOException> { Http.getText(originUrl, direct) }
        assertNull(originAuthorization.get())
    }

    @Test
    fun rejectedAuthenticationDoesNotRetryForever() {
        val calls = AtomicInteger()
        proxy.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"test\"")
            exchange.sendResponseHeaders(407, -1)
            exchange.close()
        }
        Http.configureProxy(httpConfig("test-user", "test-password"))
        assertEquals(407, assertFailsWith<HttpException> { Http.getText(originUrl) }.code)
        assertEquals(2, calls.get())
    }

    @Test
    fun unavailableCustomProxyNeverFallsBackToOrigin() {
        val port = proxy.address.port
        proxy.stop(0)
        Http.configureProxy(assertNotNull(ProxyConfig.parse(ProxyMode.HTTP, "127.0.0.1", port.toString())))
        assertFailsWith<IOException> { Http.getText(originUrl) }
    }

    @Test
    fun socksSendsDestinationHostnameToProxy() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val received = executor.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    check(input.read() == 5)
                    val methods = input.readNBytes(input.read())
                    check(0.toByte() in methods)
                    output.write(byteArrayOf(5, 0))
                    output.flush()
                    check(input.readNBytes(4).contentEquals(byteArrayOf(5, 1, 0, 3)))
                    val hostname = input.readNBytes(input.read()).toString(Charsets.US_ASCII)
                    input.readNBytes(2) // destination port
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                    output.flush()
                    val reader = input.bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nproxied".toByteArray())
                    output.flush()
                    hostname
                }
            }
            try {
                Http.configureProxy(assertNotNull(ProxyConfig.parse(ProxyMode.SOCKS, "localhost", server.localPort.toString())))
                val client = Http.client.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
                assertEquals("proxied", Http.getText("http://unresolvable.invalid/hello", client))
                assertEquals("unresolvable.invalid", received.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
        assertTrue(executor.awaitTermination(6, TimeUnit.SECONDS))
    }

    @Test
    fun httpsUsesAuthenticatedConnectTunnel() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val received = executor.submit<List<String>> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = buildList {
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                            add(line)
                        }
                    }
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    headers
                }
            }
            try {
                Http.configureProxy(assertNotNull(ProxyConfig.parse(ProxyMode.HTTP, "localhost", server.localPort.toString(), "test-user", "test-password")))
                assertFailsWith<IOException> { Http.getText("https://unresolvable.invalid/hello") }
                val headers = received.get(5, TimeUnit.SECONDS)
                assertEquals("CONNECT unresolvable.invalid:443 HTTP/1.1", headers.first())
                assertTrue(headers.contains("Proxy-Authorization: ${Credentials.basic("test-user", "test-password")}"))
            } finally {
                executor.shutdownNow()
            }
        }
        assertTrue(executor.awaitTermination(6, TimeUnit.SECONDS))
    }
}
