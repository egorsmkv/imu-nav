package org.imunav.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellSyncClient
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Checks the app's real cell-sync client against the Rust server without contacting a shared deployment. */
class CellServerApiIntegrationTest {
    @Test
    fun accountSessionAndCellSyncWorkAcrossBothImplementations() {
        val binary = System.getProperty("cellServerBinary") ?: fail("run :core:serverApiTest to build the cell server")
        val directory = Files.createTempDirectory("imu-nav-server-api-")
        val log = directory.resolve("server.log").toFile()
        val database = directory.resolve("cells.sqlite3")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val baseUrl = "http://127.0.0.1:$port"
        val process = ProcessBuilder(
            binary, "--bind", "127.0.0.1", "--port", port.toString(), "--data", database.toString(), "--area", "ukraine",
        )
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        try {
            waitUntilReady(process, baseUrl, log)
            val email = "sync-${UUID.randomUUID()}@example.org"
            val password = "temporary-test-password"
            val registration = postJson(baseUrl, "/v1/auth/register", """{"email":"$email","password":"$password"}""")
            assertEquals(200, registration.first)
            assertTrue("\"email_verified\":true" in registration.second)
            assertTrue(token(registration.second, "access_token").isNotEmpty())
            val login = postJson(baseUrl, "/v1/auth/login", """{"email":"$email","password":"$password"}""")
            assertEquals(200, login.first)
            val access = token(login.second, "access_token")
            val refresh = token(login.second, "refresh_token")

            val tower = CellTower(CellKey(Radio.LTE, 255, 1, 1864, 99), 50.4501, 30.5234, 800.0, 3)
            assertEquals(401, assertFailsWith<HttpException> { CellSyncClient(baseUrl, deviceId = "test-device-1").upload(listOf(tower)) }.code)
            val client = CellSyncClient(baseUrl, access, "test-device-1")
            assertEquals(1, client.upload(listOf(tower)))
            assertEquals(0, client.download(listOf(255), 0) { fail("one device cannot publish a tower") })
            val secondEmail = "sync-${UUID.randomUUID()}@example.org"
            val secondRegistration = postJson(baseUrl, "/v1/auth/register", """{"email":"$secondEmail","password":"$password"}""")
            assertEquals(200, secondRegistration.first)
            val secondClient = CellSyncClient(baseUrl, token(secondRegistration.second, "access_token"), "test-device-2")
            assertEquals(1, secondClient.upload(listOf(tower)))
            val downloaded = ArrayList<CellTower>()
            assertEquals(1, client.download(listOf(255), 0) { downloaded += it })
            assertEquals(tower.key, downloaded.single().key)
            assertEquals(tower.lat, downloaded.single().lat, 0.000001)
            assertEquals(0, client.download(listOf(310), 0) { fail("unexpected country in sync response") })
            val removals = client.downloadRemovals(listOf(255), 0) { fail("unexpected removal in fresh database: $it") }
            assertEquals(0, removals.count)
            assertNotNull(removals.serverEpochS)

            val renewed = postJson(baseUrl, "/v1/auth/refresh", """{"refresh_token":"$refresh"}""")
            assertEquals(200, renewed.first)
            val renewedAccess = token(renewed.second, "access_token")
            val renewedRefresh = token(renewed.second, "refresh_token")
            assertEquals(401, postJson(baseUrl, "/v1/auth/refresh", """{"refresh_token":"$refresh"}""").first)
            assertEquals(200, postJson(baseUrl, "/v1/auth/logout", """{"refresh_token":"$renewedRefresh"}""").first)
            assertEquals(401, assertFailsWith<HttpException> { CellSyncClient(baseUrl, renewedAccess, "test-device-1").upload(listOf(tower)) }.code)
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            directory.toFile().deleteRecursively()
        }
    }

    /** Readiness polling keeps the test independent of machine speed and never talks to a public server. */
    private fun waitUntilReady(process: Process, baseUrl: String, log: File) {
        val probe = Http.client.newBuilder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        repeat(100) {
            if (!process.isAlive) fail("cell server exited during startup: ${log.readText().takeLast(1000)}")
            if (runCatching { Http.getText("$baseUrl/health", probe).startsWith("ok ") }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("cell server did not become ready: ${log.readText().takeLast(1000)}")
    }

    /** Uses the same JSON endpoints and shared OkHttp client as the app's account flow. */
    private fun postJson(baseUrl: String, path: String, json: String): Pair<Int, String> {
        val request = Request.Builder().url(baseUrl + path).post(json.toRequestBody("application/json".toMediaType())).build()
        return Http.client.newCall(request).execute().use { it.code to it.body.string() }
    }

    /** The session fields are simple JSON strings; no test dependency is needed to read them. */
    private fun token(body: String, field: String): String = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        ?: fail("session response omitted $field")
}
