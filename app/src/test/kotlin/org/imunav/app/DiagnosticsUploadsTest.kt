package org.imunav.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.imunav.app.diagnostics.DiagnosticPhase
import org.imunav.app.diagnostics.ProfilePhase
import org.imunav.app.diagnostics.ProfileUploadClient
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.record.TripEvent
import org.imunav.core.route.ManeuverType
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in diagnostics, profiles and trip archives reach the sharing server only with consent. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsUploadsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: FakeSharingServer

    @Before
    fun start() {
        FakeAndroidKeyStore.install()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        server = FakeSharingServer()
    }

    @After
    fun stop() = server.close()

    private fun waitFor(what: String, detail: () -> String = { "" }, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${detail()} ${server.requests}" }
            ShadowLooper.idleMainLooper(STEP_MS, TimeUnit.MILLISECONDS)
            Thread.sleep(STEP_MS)
        }
    }

    private fun signedIn(): AppGraph {
        val graph = AppGraph(context)
        graph.cells.saveSettings(server.url, autoSync = false, mccs = "255")
        graph.cells.authenticate(FakeSharingServer.EMAIL, "secret", register = false)
        waitFor("sign-in") { graph.cells.status.value.busy == null && graph.cells.diagnosticAccountEmail() != null }
        return graph
    }

    private fun grant(graph: AppGraph, purpose: String) {
        val notice = checkNotNull(runBlocking { graph.cells.privacyNotice(server.url) })
        runBlocking { graph.cells.grantPrivacyConsent(purpose, notice.version, checkNotNull(graph.cells.diagnosticOwner())) }
    }

    @Test
    fun diagnosticSessionIsRecordedAndUploadedWithConsent() {
        val graph = signedIn()
        val diagnostics = graph.diagnostics
        diagnostics.setEnabled(true)
        diagnostics.startTrip("t1", JSONObject().put("mode", "CAR"))
        assertEquals("no consent yet: nothing is recorded", DiagnosticPhase.WAITING, diagnostics.status.value.phase)

        grant(graph, "diagnostics")
        assertTrue(graph.cells.diagnosticConsentGranted())
        diagnostics.startTrip("t2", JSONObject().put("mode", "CAR"))
        waitFor("recording", { diagnostics.status.value.toString() }) { diagnostics.status.value.phase == DiagnosticPhase.RECORDING }
        diagnostics.onEvent("t2", TripEvent.VehicleSpeed(SystemClock.elapsedRealtime(), 42f))
        diagnostics.onLog("gps_state=OK token=secret", SystemClock.elapsedRealtime())
        diagnostics.endTrip("t2")
        // Server receipt precedes client completion; disabling mid-response tests cancellation instead of a completed upload.
        waitFor("upload", { diagnostics.status.value.toString() }) {
            server.debugBatches.any { it.endsWith("/finish") } && diagnostics.status.value.phase == DiagnosticPhase.UPLOADED
        }
        assertTrue(server.requests.contains("POST /v1/debug/sessions"))

        diagnostics.setEnabled(false)
        assertEquals(DiagnosticPhase.IDLE, diagnostics.status.value.phase)
        graph.cells.withdrawPrivacyConsent("diagnostics")
    }

    @Test
    fun profileIsCapturedSharedAndUploaded() {
        val graph = signedIn()
        val profile = graph.profileCapture
        profile.start()
        waitFor("profile recording", { profile.status.value.toString() }) { profile.status.value.phase == ProfilePhase.RECORDING }
        profile.onEvent(TripEvent.Pressure(SystemClock.elapsedRealtime(), 1013f))
        profile.onLog("frame_ms=12", SystemClock.elapsedRealtime())
        profile.stop()
        waitFor("profile archive", { profile.status.value.toString() }) { profile.status.value.phase == ProfilePhase.READY }

        val credentials = checkNotNull(graph.cells.accountCredentials())
        val client = ProfileUploadClient(credentials.first, credentials.second)
        val zip = TemporaryFolder().apply { create() }.newFile("profile.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("started.json"))
            it.write("{}".toByteArray())
            it.closeEntry()
        }
        runBlocking {
            assertEquals("2026-10", client.metadata().getString("notice_version"))
            client.consent("2026-10")
            assertEquals("profile-1", client.upload(zip, FakeSharingServer.MAX_UPLOAD.toLong()))
        }
        assertEquals(1, server.uploadedProfiles)
        assertTrue("diagnostics" in server.consents)

        // AndroidX FileProvider checks roots with a hard-coded '/', so sharing works on Android but not on a Windows host.
        assumeTrue("POSIX paths", File.separatorChar == '/')
        assertTrue(profile.shareIntent().hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun finishedTripIsArchivedAutomaticallyOnceEnabled() {
        val graph = signedIn()
        val uploads = graph.automaticTripUpload
        uploads.refresh()
        waitFor("archive available", { uploads.status.value.toString() }) { uploads.status.value.available && !uploads.status.value.busy }
        uploads.setEnabled(true)
        waitFor("enabled", { uploads.status.value.toString() }) { uploads.status.value.enabled && !uploads.status.value.busy }

        val projection = LocalProjection(GeoPoint(50.45, 30.52))
        val route = Route(
            (0..2000 step 20).map { projection.toGeo(0.0, it.toDouble()) },
            listOf(Step(ManeuverType.DEPART, null, "Khreshchatyk", 2000.0, 150.0, 0), Step(ManeuverType.ARRIVE, null, "", 0.0, 0.0, 100)),
            150.0,
        )
        val started = SystemClock.elapsedRealtime()
        graph.engine.start(route, route.geometry.last(), emptyList(), started)
        graph.trips.begin(route, route.geometry.last(), emptyList(), startAccuracyM = 20.0, mode = TravelMode.CAR)
        graph.trips.onTick(started + 500)
        graph.trips.onTick(started + 1500)
        graph.trips.end(arrived = true, keepShortTrip = true)
        waitFor("trip upload", { uploads.status.value.toString() }) { server.uploadedTrips.isNotEmpty() }
        assertEquals(graph.trips.history.value.single().id, server.uploadedTrips.single())

        uploads.setEnabled(false)
        waitFor("disabled") { !uploads.status.value.enabled }
        assertTrue(File(context.filesDir, "trips").isDirectory)
    }
}

private const val STEP_MS = 50L
