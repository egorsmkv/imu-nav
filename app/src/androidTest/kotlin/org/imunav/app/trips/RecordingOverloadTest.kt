package org.imunav.app.trips

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.record.RouteCodec
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.route.Route
import org.imunav.core.util.OrderedBuffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Deliberately stalls private storage, exercising main-looper failure delivery with synthetic trips only. */
@RunWith(AndroidJUnit4::class)
class RecordingOverloadTest {
    @Test
    fun delayedFailureCannotDetachANewRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "overload-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val io = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        io.execute { release.await() }
        val hub = PositioningHub()
        val engine = NavigationEngine(listener = object : NavListener {})
        val trips = TripManager(context, hub, engine, {}, io, OrderedBuffer(io, 4, 4096))
        val destination = GeoPoint(50.451, 30.521)
        val route = Route(listOf(GeoPoint(50.45, 30.52), destination), emptyList(), 30.0)
        try {
            instrumentation.runOnMainSync {
                trips.begin(route, destination, emptyList(), 10.0)
                trips.onStep(10)
                assertEquals(RecordingStatus.OVERLOAD, trips.recordingStatus.value)
                trips.end(arrived = false, keepShortTrip = true)
                release.countDown()
                // Deliberately hold main until old disk work completes, leaving its failure callback queued.
                io.submit {}.get(10, TimeUnit.SECONDS)
                trips.begin(route, destination, emptyList(), 10.0)
                io.submit {}.get(10, TimeUnit.SECONDS)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(RecordingStatus.RECORDING, trips.recordingStatus.value)
                trips.onStep(20)
                trips.end(arrived = false, keepShortTrip = true)
            }
            io.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(2, trips.history.value.size)
            val complete = trips.history.value.single { !it.recordingStatus.partial }
            assertTrue(TripFormat.read(trips.recordingFile(complete)).any { it is TripEvent.StepTaken && it.elapsedMs == 20L })
            assertEquals(1, trips.history.value.count { it.recordingStatus == RecordingStatus.OVERLOAD })
        } finally {
            release.countDown()
            io.shutdown()
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test
    fun rapidRestartKeepsNewSnapshotWhenOldCleanupIsQueuedBehindIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "snapshot-restart-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val io = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        io.execute { release.await() }
        val trips = TripManager(context, PositioningHub(), NavigationEngine(listener = object : NavListener {}), {}, io)
        val destination = GeoPoint(50.451, 30.521)
        val route = Route(listOf(GeoPoint(50.45, 30.52), destination), emptyList(), 30.0)
        try {
            instrumentation.runOnMainSync {
                trips.begin(route, destination, emptyList(), 10.0)
                trips.end(arrived = false, keepShortTrip = true)
                trips.begin(route, destination, emptyList(), 10.0)
            }
            release.countDown()
            io.submit {}.get(10, TimeUnit.SECONDS)
            val old = trips.history.value.single()
            val saved = JSONObject(File(directory, "trips/active.json").readText())
            assertTrue(saved.getString("id") != old.id)
            assertEquals(RecordingStatus.RECORDING.name, saved.getString("recordingStatus"))
            instrumentation.runOnMainSync { trips.end(arrived = false, keepShortTrip = true) }
            io.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(2, trips.history.value.size)
        } finally {
            release.countDown()
            io.shutdown()
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test
    fun restoringPartialTripKeepsGuidanceActiveWithoutReopeningRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "partial-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val io = Executors.newSingleThreadExecutor()
        val hub = PositioningHub()
        val engine = NavigationEngine(listener = object : NavListener {})
        val trips = TripManager(context, hub, engine, {}, io)
        val route = Route(listOf(GeoPoint(50.45, 30.52), GeoPoint(50.451, 30.521)), emptyList(), 30.0)
        val now = System.currentTimeMillis()
        val snapshot = JSONObject().put("id", "partial-trip").put("recording", "partial-trip.rec.gz")
            .put("start", now - 1000).put("savedAt", now).put("destLat", 50.451).put("destLon", 30.521)
            .put("route", RouteCodec.encode(route)).put("s", 20.0).put("unc", 100.0)
            .put("recordingStatus", RecordingStatus.OVERLOAD.name)
        try {
            val restored = RestoredTrip.decode(snapshot, now)
            instrumentation.runOnMainSync {
                assertTrue(trips.restore(restored))
                assertTrue(engine.state.active)
                assertNull(hub.recorder)
                assertEquals(RecordingStatus.OVERLOAD, trips.recordingStatus.value)
                trips.onStep(20)
            }
            io.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(0, trips.recordingStats.items)
            assertTrue(File(directory, "trips/active.json").readText().contains("OVERLOAD"))
            assertTrue(!File(directory, "trips/partial-trip.rec.gz").exists())
            instrumentation.runOnMainSync { trips.end(arrived = false, keepShortTrip = true) }
            io.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(RecordingStatus.OVERLOAD, trips.history.value.single().recordingStatus)
        } finally {
            io.shutdown()
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }
}
