package org.imunav.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.imunav.app.AppGraph
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Two isolated installations exercise the real Android coordinator against a disposable Rust server. */
@RunWith(AndroidJUnit4::class)
class AccountSyncIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun loginRestoresOfflineEditsAndSignOutReturnsToLocalProfile() {
        val server = InstrumentationRegistry.getArguments().getString("syncServer").orEmpty()
        assumeTrue("Pass syncServer pointing at a disposable local Rust server", server.isNotEmpty())
        val email = "sync-${UUID.randomUUID()}@example.org"
        val password = "test account password only"
        lateinit var first: AppGraph
        lateinit var second: AppGraph
        main {
            first = AppGraph(IsolatedContext(instrumentation.targetContext))
            second = AppGraph(IsolatedContext(instrumentation.targetContext))
            first.accountSync.setVisible(true)
            second.accountSync.setVisible(true)
        }
        try {
            await { first.bookmarks.state.value.loaded && second.bookmarks.state.value.loaded }
            main {
                first.setVoiceEnabled(false)
                first.bookmarks.edit(SavedPlace("home", "Original home", BookmarkPoint(GeoPoint(50.45, 30.52))))
                first.bookmarks.save()
            }
            await { !first.bookmarks.state.value.busy }
            login(first, server, email, password, true)
            await { first.accountSync.status.value.prompt }
            main { first.accountSync.choose(true) }
            await { first.accountSync.status.value.lastSuccess > 0 }
            login(second, server, email, password, false)
            await { second.accountSync.status.value.prompt }
            main { second.accountSync.choose(true) }
            await { second.accountSync.status.value.lastSuccess > 0 }
            assertFalse(second.voiceEnabled.value)
            assertEquals("Original home", second.bookmarks.state.value.items.single().name)
            main {
                second.accountSync.setVisible(false)
                second.bookmarks.edit(second.bookmarks.state.value.items.single())
                second.bookmarks.nameChanged("Edited offline")
                second.bookmarks.save()
            }
            await { !second.bookmarks.state.value.busy }
            val previousSync = second.accountSync.status.value.lastSuccess
            main { second.accountSync.setVisible(true) }
            await { second.accountSync.status.value.lastSuccess > previousSync }
            main { first.accountSync.syncNow() }
            await { first.bookmarks.state.value.items.singleOrNull()?.name == "Edited offline" }
            main { first.cells.signOut() }
            await { !first.accountSync.status.value.signedIn && first.bookmarks.state.value.items.singleOrNull()?.name == "Original home" }
            assertFalse(first.voiceEnabled.value)
            main { second.cells.signOut() }
            await { !second.accountSync.status.value.signedIn && second.bookmarks.state.value.items.isEmpty() }
            assertTrue(second.voiceEnabled.value)
            login(second, server, email, password, false)
            await { second.accountSync.status.value.enabled && second.bookmarks.state.value.items.singleOrNull()?.name == "Edited offline" }
            assertFalse(second.accountSync.status.value.prompt)
            main { second.accountSync.choose(false) }
            await { !second.accountSync.status.value.enabled && !second.accountSync.status.value.busy }
            awaitServerErasure(second)
            main { second.cells.signOut() }
            await { !second.accountSync.status.value.signedIn && second.bookmarks.state.value.items.isEmpty() }
            login(second, server, "other-$email", password, true)
            await { second.accountSync.status.value.prompt }
            main { second.accountSync.choose(true) }
            await { second.accountSync.status.value.lastSuccess > 0 }
            assertTrue(second.bookmarks.state.value.items.isEmpty())
            assertTrue(second.voiceEnabled.value)
        } finally {
            main {
                first.accountSync.setVisible(false)
                second.accountSync.setVisible(false)
                first.scope.cancel()
                second.scope.cancel()
            }
        }
    }

    private fun login(app: AppGraph, server: String, email: String, password: String, register: Boolean) = main {
        app.cells.saveSettings(server, false, "255")
        app.cells.authenticate(email, password, register)
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Check the server acknowledgement: the switch turns off before queued erasure completes. */
    private fun awaitServerErasure(app: AppGraph) = runBlocking {
        val credentials = requireNotNull(app.cells.accountCredentials())
        val api = SyncApi(credentials.first, credentials.second)
        withTimeout(30_000) {
            while (true) {
                val snapshot = api.request()
                if (!snapshot.getBoolean("enabled") && snapshot.getLong("generation") > 0) {
                    assertEquals(0, snapshot.getJSONArray("entries").length())
                    break
                }
                delay(100)
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(100)
        }
        error("Timed out waiting for synchronization")
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
