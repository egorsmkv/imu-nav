package org.imunav.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.imunav.app.cells.CellSource
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.imunav.core.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** The account, tower sharing, account sync and air-alert clients complete their real exchanges with a server. */
@RunWith(RobolectricTestRunner::class)
class SharingClientsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: FakeSharingServer

    @Before
    fun start() {
        FakeAndroidKeyStore.install()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        server = FakeSharingServer()
    }

    @After
    fun stop() = server.close()

    private var detail: () -> String = { "" }

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${detail()}" }
            // Advance the main looper's virtual clock too, so debounce and retry delays elapse.
            ShadowLooper.idleMainLooper(DEBOUNCE_STEP_MS, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
    }

    /** A graph signed in to the fake server with tower upload consent. */
    private fun signedIn(): AppGraph {
        val graph = AppGraph(context)
        graph.cells.saveSettings(server.url, autoSync = true, mccs = "255")
        graph.cells.authenticate(FakeSharingServer.EMAIL, "secret", register = false)
        detail =
            {
                "cells=${graph.cells.status.value.busy}|${graph.cells.status.value.message}|${graph.cells.status.value.lastSync} sync=${graph.accountSync.status.value} requests=${server.requests}"
            }
        waitFor("sign-in") { graph.cells.status.value.busy == null && graph.cells.diagnosticAccountEmail() != null }
        return graph
    }

    @Test
    fun towersAreUploadedWithConsentAndSharedTowersDownloaded() {
        val graph = signedIn()
        val notice = checkNotNull(runBlocking { graph.cells.privacyNotice(server.url) })
        assertEquals("Повідомлення", notice.text("uk"))
        val owner = checkNotNull(graph.cells.diagnosticOwner())
        runBlocking { graph.cells.grantPrivacyConsent("tower_upload", notice.version, owner) }
        assertTrue(graph.cells.privacyGranted("tower_upload", notice.version))
        assertTrue("tower_upload" in server.consents)

        graph.cells.db.learn(listOf(CellKey(Radio.LTE, 255, 1, 100, 25_600)), 50.45, 30.52, accuracyM = 10.0)
        val shared = CellTower(CellKey(Radio.LTE, 255, 1, 100, 51_200), 49.84, 24.03, 900.0, 8)
        server.sharedTowers += shared
        server.removedKeys += CellKey(Radio.LTE, 255, 1, 100, 76_800)
        graph.cells.sync()
        waitFor("tower sync") { graph.cells.status.value.busy == null && server.requests.contains("GET /v1/cells.csv.gz") }
        assertEquals(1, server.uploadedTowers)
        assertNotNull(graph.cells.db.resolve(shared.key))
        assertEquals(1L, graph.cells.db.counts()[CellSource.SHARED])
        assertTrue(server.requests.contains("GET /v1/cells/removals.csv"))

        graph.cells.withdrawPrivacyConsent("tower_upload")
        graph.cells.sync()
        waitFor("sync after withdrawal") { graph.cells.status.value.busy == null && "tower_upload" !in server.consents }

        graph.cells.requestPasswordReset(FakeSharingServer.EMAIL)
        waitFor("reset request") { graph.cells.status.value.busy == null && server.requests.contains("POST /v1/auth/password-reset/request") }
        graph.cells.signOut()
        waitFor("sign-out") { graph.cells.status.value.busy == null && graph.cells.diagnosticAccountEmail() == null }
        assertTrue(server.requests.contains("POST /v1/auth/logout"))
    }

    @Test
    fun accountSyncAsksForConsentThenExchangesBookmarksAndSettings() {
        val graph = signedIn()
        waitFor("bookmarks") { graph.bookmarks.state.value.loaded }
        graph.bookmarks.edit(SavedPlace("home", "Home", BookmarkPoint(GeoPoint(50.45, 30.52), "Khreshchatyk")))
        graph.bookmarks.save()
        waitFor("bookmark saved") { graph.bookmarks.state.value.items.isNotEmpty() }
        val sync = graph.accountSync
        sync.setVisible(true)
        waitFor("consent prompt") { sync.status.value.prompt }
        assertTrue(sync.status.value.signedIn)
        assertEquals("Notice", sync.status.value.notice)

        // The journal is saved with Android's AtomicFile, which replaces files by renaming: that works on
        // Android and Linux but not on a Windows development host.
        assumeTrue("POSIX rename semantics", File.separatorChar == '/')

        sync.choose(true)
        waitFor("first exchange") { sync.status.value.lastSuccess > 0 && !sync.status.value.busy }
        assertTrue("account_sync" in server.consents)
        assertTrue(server.requests.contains("PUT /v1/account-sync"))
        assertTrue(sync.status.value.enabled)

        sync.syncNow()
        sync.choose(false)
        waitFor("withdrawal") { "account_sync" !in server.consents }
        assertFalse(sync.status.value.enabled)
        sync.requestEnable()
        sync.decline()
        sync.setVisible(false)
    }

    @Test
    fun airAlertsFollowTheAccountPreferenceAndTheLiveFeed() {
        val graph = signedIn()
        val alerts = graph.airAlerts
        alerts.setVisible(true)
        waitFor("preference") { alerts.status.value.signedIn }
        assertFalse(alerts.status.value.enabled)

        runBlocking { alerts.setEnabled(true) }
        assertTrue(server.airAlertsEnabled)
        waitFor("live feed") { alerts.status.value.active.map { it.id } == listOf("lviv") }
        assertFalse(alerts.status.value.stale)

        alerts.setVisible(false)
        assertTrue(alerts.status.value.active.isEmpty())
        assertTrue(alerts.status.value.stale)
        assertNull(alerts.status.value.active.firstOrNull())
    }
}

private const val DEBOUNCE_STEP_MS = 200L
