package org.imunav.app.diagnostics

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.imunav.core.record.TripEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/** A profile session is packaged into one bounded ZIP; a killed session is salvaged on the next start. */
@RunWith(RobolectricTestRunner::class)
class ProfileCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sessions = File(context.filesDir, "profiles")
    private val shares = File(context.cacheDir, "profile-shares")

    @Before
    fun clean() {
        sessions.deleteRecursively()
        shares.deleteRecursively()
    }

    private fun waitFor(capture: ProfileCapture, vararg phases: ProfilePhase) {
        val deadline = System.currentTimeMillis() + 15_000
        while (capture.status.value.phase !in phases) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $phases: ${capture.status.value}" }
            Thread.sleep(STEP_MS)
        }
    }

    private fun entries(archive: File): Map<String, String> = ZipFile(archive).use { zip ->
        zip.entries().toList().associate { it.name to zip.getInputStream(it).bufferedReader().readText() }
    }

    private fun record(capture: ProfileCapture): File {
        capture.start()
        waitFor(capture, ProfilePhase.RECORDING)
        capture.onEvent(TripEvent.Pressure(SystemClock.elapsedRealtime(), 1013f))
        capture.onLog("sync token=secret frame_ms=12", SystemClock.elapsedRealtime())
        capture.stop()
        waitFor(capture, ProfilePhase.READY, ProfilePhase.INTERRUPTED, ProfilePhase.ERROR)
        assertEquals(ProfilePhase.READY, capture.status.value.phase)
        return checkNotNull(capture.status.value.archive)
    }

    @Test
    fun finishedSessionHoldsManifestLogsEventsAndMemory() {
        val capture = ProfileCapture(context) { mapOf("fps" to 30) }
        val files = entries(record(capture))
        val manifest = JSONObject(checkNotNull(files["manifest.json"]))
        assertFalse(manifest.getBoolean("interrupted"))
        assertEquals("complete", manifest.getString("stream_status"))
        assertEquals(30, manifest.getJSONObject("start").getJSONObject("rendering").getInt("fps"))
        assertTrue(checkNotNull(files["memory.csv"]).startsWith("elapsed_ms,java_heap_used_bytes"))
        assertTrue(checkNotNull(files["trip-events.rec"]).isNotBlank())
        val logs = checkNotNull(files["logs.txt"])
        assertTrue(logs.contains("frame_ms=12"))
        assertFalse("secrets are redacted before they reach the archive", logs.contains("secret"))
        assertFalse("the session folder is removed once packaged", sessions.listFiles().orEmpty().any(File::isDirectory))

        // A restarted app offers the last archive again.
        val restarted = ProfileCapture(context)
        waitFor(restarted, ProfilePhase.READY)
        assertEquals(capture.status.value.archive, restarted.status.value.archive)
    }

    @Test
    fun onlyTheNewestArchivesAreKept() {
        val capture = ProfileCapture(context)
        val archives = (1..4).map {
            record(capture).also { archive -> archive.setLastModified(System.currentTimeMillis() - (10 - it) * 1000L) }
        }
        val kept = shares.listFiles().orEmpty().filter { it.extension == "zip" }.toSet()
        assertTrue(archives.last() in kept)
        assertTrue("at most three archives remain", kept.size <= 3)
        assertFalse(archives.first() in kept)
    }

    @Test
    fun sessionKilledMidRecordingIsPackagedAsPartial() {
        val killed = File(sessions, "session-1").apply { mkdirs() }
        File(killed, "started.json").writeText(JSONObject().put("schema", 2).put("build_type", "benchmark").toString())
        File(killed, "logs.txt").writeText("gps_state=OK\n")
        File(killed, "methods.trace").writeText("unclosed")
        val capture = ProfileCapture(context)
        waitFor(capture, ProfilePhase.INTERRUPTED, ProfilePhase.ERROR)
        assertEquals(ProfilePhase.INTERRUPTED, capture.status.value.phase)
        val archive = checkNotNull(capture.status.value.archive)
        assertTrue(archive.name.startsWith("imu-nav-profile-partial-"))
        val files = entries(archive)
        assertFalse("an unclosed method trace is never shared", "methods.trace" in files)
        val manifest = JSONObject(checkNotNull(files["manifest.json"]))
        assertTrue(manifest.getBoolean("interrupted"))
        assertEquals("unclosed", manifest.getString("trace_status"))
        assertEquals("benchmark", manifest.getString("build_type"))
        assertFalse(killed.exists())

        // A new recording can start after the salvaged one.
        record(capture)
    }

    @Test
    fun folderWithoutStartMarkerIsLeftAlone() {
        val stray = File(sessions, "session-2").apply { mkdirs() }
        val capture = ProfileCapture(context)
        Thread.sleep(200)
        assertEquals(ProfilePhase.IDLE, capture.status.value.phase)
        assertTrue(stray.isDirectory)
        capture.stop()
        assertEquals("stop without a recording has no effect", ProfilePhase.IDLE, capture.status.value.phase)
    }
}

private const val STEP_MS = 20L
