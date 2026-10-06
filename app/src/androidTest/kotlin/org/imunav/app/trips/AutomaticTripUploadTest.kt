package org.imunav.app.trips

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.imunav.app.AppGraph
import org.imunav.app.sync.SyncApi
import org.imunav.app.sync.SyncDisk
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real server checks for local opt-in, account ownership, consent withdrawal and historical trips. */
@RunWith(AndroidJUnit4::class)
class AutomaticTripUploadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun onlyConsentedFutureCompletionsUploadToTheirOriginalAccount() {
        val server = InstrumentationRegistry.getArguments().getString("tripServer").orEmpty()
        assumeTrue(server.isNotEmpty())
        val context = IsolatedContext(instrumentation.targetContext)
        val directory = File(context.filesDir, "trips").apply { mkdirs() }
        val trips = listOf("old", "new", "different-account").map { id ->
            File(directory, "$id.rec").writeText("E,100,50,30,0,5,GPS\nE,1100,50.001,30.001,100,5,GPS\nX,2100\n")
            TripSummary(id, 1000, 3000, GeoPoint(50.001, 30.001), true, 100.0, 2.0, 2.0, 0.0, 0.0, 5.0, 100.0, 0, "$id.rec")
        }
        File(directory, "index.jsonl").writeText(trips.joinToString("\n", postfix = "\n") { it.toJson().toString() })
        lateinit var app: AppGraph
        main { app = AppGraph(context) }
        try {
            await { app.trips.history.value.size == 3 }
            fun login() {
                main {
                    app.cells.saveSettings(server, false, "255")
                    app.cells.authenticate("auto-${UUID.randomUUID()}@example.org", "synthetic test password", true)
                }
                await { app.cells.accountId() > 0 && !app.automaticTripUpload.status.value.busy }
            }
            fun api(): SyncApi {
                val credentials = requireNotNull(app.cells.accountCredentials())
                return SyncApi(credentials.first, credentials.second)
            }
            login()
            val first = api()
            main { app.automaticTripUpload.setEnabled(true) }
            assertFalse(app.automaticTripUpload.status.value.enabled)
            runBlocking { TripArchiveClient(server, requireNotNull(app.cells.accountCredentials()).second).consent("local") }
            main { app.automaticTripUpload.refresh() }
            await { app.automaticTripUpload.status.value.available && !app.automaticTripUpload.status.value.busy }
            main { app.automaticTripUpload.setEnabled(true) }
            await { app.automaticTripUpload.status.value.enabled && !app.automaticTripUpload.status.value.busy }
            assertEquals(0, runBlocking { first.request("/v1/trips").getInt("count") })
            main {
                app.automaticTripUpload.started("new")
                app.automaticTripUpload.completed("new")
            }
            await { runBlocking { first.request("/v1/trips").getInt("count") } == 1 }
            assertEquals("new", runBlocking { first.request("/v1/trips").getJSONArray("trips").getJSONObject(0).getString("id") })
            main {
                app.automaticTripUpload.started("different-account")
                app.cells.signOut()
            }
            await { app.cells.accountId() == 0L }
            login()
            val second = api()
            runBlocking { TripArchiveClient(server, requireNotNull(app.cells.accountCredentials()).second).consent("local") }
            main { app.automaticTripUpload.refresh() }
            await { app.automaticTripUpload.status.value.available && !app.automaticTripUpload.status.value.busy }
            assertFalse(app.automaticTripUpload.status.value.enabled)
            main { app.automaticTripUpload.setEnabled(true) }
            await { app.automaticTripUpload.status.value.enabled && !app.automaticTripUpload.status.value.busy }
            val secondProfile = SyncDisk.profile(server, app.cells.accountId(), app.cells.syncIdentity())
            main { app.automaticTripUpload.completed("different-account") }
            await {
                val preferences = context.getSharedPreferences("automatic_trip_upload", Context.MODE_PRIVATE)
                !preferences.contains("owner:different-account") && preferences.getStringSet("pending:$secondProfile", emptySet()).orEmpty().isEmpty() &&
                    !app.automaticTripUpload.status.value.busy
            }
            assertEquals(0, runBlocking { second.request("/v1/trips").getInt("count") })
            runBlocking { second.request("/v1/privacy/consents/trip_archive", "DELETE") }
            main { app.automaticTripUpload.refresh() }
            await { !app.automaticTripUpload.status.value.available && !app.automaticTripUpload.status.value.enabled }
        } finally {
            main { app.scope.cancel() }
        }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for automatic upload")
    }

    /** Keeps test accounts away from the installed app's real preferences, files and databases. */
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "sync-test-${UUID.randomUUID()}"
        private val root = File(base.filesDir, prefix).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$prefix-$name", mode)
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }
}
