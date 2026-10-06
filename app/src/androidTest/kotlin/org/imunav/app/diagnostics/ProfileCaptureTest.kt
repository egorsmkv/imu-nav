package org.imunav.app.diagnostics

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.app.nativecore.NativeSpeedFusion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Exercises real Android tracing, ZIP creation and FileProvider access in an installed app. */
@RunWith(AndroidJUnit4::class)
class ProfileCaptureTest {
    @Test
    fun restartExportsFlushedFilesWithoutAnUnclosedTrace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "profiles/session-recovery-test").apply { mkdirs() }
        File(directory, "started.json").writeText(JSONObject().put("start_wall_ms", 1).put("start_elapsed_ms", 1).toString())
        File(directory, "logs.txt").writeText("recoverable log\n")
        File(directory, "methods.trace").writeText("unfinished trace")

        val capture = ProfileCapture(context)
        val recovered = await(capture, ProfilePhase.INTERRUPTED)
        ZipFile(requireNotNull(recovered.archive)).use { zip ->
            val manifest = JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader().readText())
            assertTrue(manifest.getBoolean("interrupted"))
            assertEquals(1, manifest.getInt("schema"))
            assertEquals("unclosed", manifest.getString("trace_status"))
            assertNotNull(zip.getEntry("logs.txt"))
            assertNull(zip.getEntry("methods.trace"))
        }
        recovered.archive?.delete()
    }

    @Test
    fun stoppedCaptureExportsBoundedDiagnosticsThroughFileProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = ProfileCapture(context) { mapOf("map_max_fps" to 15) }
        capture.onLog("before start", 0)
        capture.start()
        await(capture, ProfilePhase.RECORDING)
        capture.onLog("profile_test token=secret", 0)
        NativeSpeedFusion.fuse(10.0, 0, 8.0, null)
        Thread.sleep(6_000) // Cross one cheap sample interval, but not the next detailed scan.
        capture.stop()
        val ready = await(capture, ProfilePhase.READY)
        val archive = requireNotNull(ready.archive)
        assertTrue(archive.isFile)
        ZipFile(archive).use { zip ->
            val manifest = JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader().readText())
            assertFalse(manifest.getBoolean("interrupted"))
            assertEquals("complete", manifest.getString("trace_status"))
            assertEquals(2, manifest.getInt("schema"))
            assertEquals(15, manifest.getJSONObject("start").getJSONObject("rendering").getInt("map_max_fps"))
            assertEquals(15_000, manifest.getInt("detailed_sample_interval_ms"))
            assertTrue(manifest.getString("build_type").isNotBlank())
            assertTrue(manifest.getString("source_revision").isNotBlank())
            assertNotNull(zip.getEntry("methods.trace"))
            assertNotNull(zip.getEntry("memory.csv"))
            val rows = zip.getInputStream(requireNotNull(zip.getEntry("memory.csv"))).bufferedReader().readLines()
            val columns = rows.first().split(',')
            assertTrue(columns.contains("summary_graphics_kb"))
            val samples = rows.drop(1).map { it.split(',') }
            assertTrue(samples.isNotEmpty())
            assertTrue(samples.all { it.size == columns.size })
            assertTrue(samples.any { it[columns.indexOf("total_pss_kb")].isBlank() && it[columns.indexOf("summary_graphics_kb")].isBlank() })
            assertTrue(samples.first()[columns.indexOf("total_pss_kb")].isNotBlank())
            assertTrue(samples.last()[columns.indexOf("total_pss_kb")].isNotBlank())
            assertNotNull(zip.getEntry("native-timings.json"))
            val timings = JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("native-timings.json"))).bufferedReader().readText())
            assertTrue(timings.getJSONObject("operations").getJSONObject("speed_fuse").getInt("calls") >= 1)
            val logs = zip.getInputStream(requireNotNull(zip.getEntry("logs.txt"))).bufferedReader().readText()
            assertTrue(logs.contains("token=[redacted]"))
            assertFalse(logs.contains("token=secret"))
            assertFalse(logs.contains("before start"))
        }
        val intent = capture.shareIntent()
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        val uri = requireNotNull(intent.clipData).getItemAt(0).uri
        context.contentResolver.openInputStream(uri).use { stream -> assertTrue(requireNotNull(stream).read() >= 0) }
        File(archive.path).delete()
    }

    private fun await(capture: ProfileCapture, phase: ProfilePhase): ProfileStatus {
        repeat(200) {
            val status = capture.status.value
            if (status.phase == phase) return status
            if (status.phase == ProfilePhase.ERROR) error("Profile capture failed: ${status.detail}")
            Thread.sleep(100)
        }
        error("Timed out waiting for $phase; current=${capture.status.value.phase}")
    }
}
