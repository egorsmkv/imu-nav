package org.imunav.app.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.imunav.app.sync.SyncApi
import org.imunav.core.geo.GeoPoint
import org.imunav.core.net.HttpException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors

/** Exercises real Android JSON and the private archive API with synthetic recordings only. */
@RunWith(AndroidJUnit4::class)
class TripArchiveTest {
    /** Leaving the screen or switching its account cancels the same job, including its socket. */
    @Test fun cancellingAnArchiveRequestClosesTheConnection() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Boolean>()
        val worker = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            worker.execute {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume only request headers. */ }
                    accepted.complete(Unit)
                    closed.complete(reader.read() == -1)
                }
            }
            val request = launch { TripArchiveClient("http://127.0.0.1:${server.localPort}", "synthetic-token").metadata() }
            try {
                withTimeout(10_000) {
                    accepted.await()
                    request.cancelAndJoin()
                    assertTrue(closed.await())
                }
            } finally {
                request.cancelAndJoin()
                worker.shutdownNow()
            }
        }
    }

    @Test fun exportAndManualUploadCanRetryThenBeErased() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("tripServer").orEmpty()
        assumeTrue("Supply a disposable local tripServer", server.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("trip-playback", ".rec", context.cacheDir)
        try {
            file.writeText("E,100,50.45,30.52,0,5,GPS\nE,1100,50.451,30.521,100,20,DR+NET\nX,2100\n")
            val trip = TripSummary("test-${UUID.randomUUID()}", 1000, 3000, GeoPoint(50.451, 30.521), true, 100.0, 2.0, 2.0, 1.0, 50.0, 20.0, 100.0, 0, file.name)
            val document = playbackDocument(file, trip)
            assertEquals("DR_NET", document.getJSONArray("positions").getJSONObject(1).getString("source"))
            assertFalse(document.toString().contains(file.name))
            val login = SyncApi(
                server,
                "",
            ).request("/v1/auth/register", "POST", JSONObject().put("email", "trip-${UUID.randomUUID()}@example.org").put("password", "synthetic test password"))
            val token = login.getString("access_token")
            val client = TripArchiveClient(server, token)
            val api = SyncApi(server, token)
            val metadata = client.metadata()
            assertFalse(metadata.getBoolean("enabled"))
            client.consent(metadata.getString("notice_version"))
            client.upload(trip.id, document, metadata.getJSONObject("limits").getInt("upload_bytes"))
            client.upload(trip.id, document, metadata.getJSONObject("limits").getInt("upload_bytes"))
            assertEquals(1, client.metadata().getInt("count"))
            val downloaded = api.request("/v1/trips/${trip.id}")
            assertEquals(2, downloaded.getJSONArray("positions").length())
            api.request("/v1/privacy/consents/trip_archive", "DELETE")
            assertEquals(0, client.metadata().getInt("count"))
            try {
                client.upload(trip.id, document, metadata.getJSONObject("limits").getInt("upload_bytes"))
                error("Upload after withdrawal succeeded")
            } catch (expected: HttpException) {
                assertEquals(409, expected.code)
            }
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }
}
