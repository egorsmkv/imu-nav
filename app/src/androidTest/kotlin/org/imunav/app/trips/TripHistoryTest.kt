package org.imunav.app.trips

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.route.Route
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks real recording closure and history persistence in an isolated private directory. */
@RunWith(AndroidJUnit4::class)
class TripHistoryTest {
    @Test
    fun explicitStopKeepsStationaryRideOnceAndCleanupStillDiscardsAccidentalStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "history-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val trips = TripManager(context, PositioningHub(), NavigationEngine(listener = object : NavListener {}), {})
        val destination = GeoPoint(50.451, 30.521)
        val route = Route(listOf(GeoPoint(50.45, 30.52), destination), emptyList(), 30.0)
        try {
            val saved = CountDownLatch(1)
            trips.onArchiveCompleted = { saved.countDown() }
            instrumentation.runOnMainSync {
                trips.begin(route, destination, emptyList(), 10.0)
                trips.end(arrived = false, keepShortTrip = true)
                trips.end(arrived = false, keepShortTrip = true)
            }
            assertTrue(saved.await(10, TimeUnit.SECONDS))
            val summary = trips.history.value.single()
            assertEquals(0.0, summary.drivenM, 0.0)
            assertFalse(summary.arrived)
            assertFalse(trips.active)
            val events = TripFormat.read(trips.recordingFile(summary)).toList()
            assertTrue(events.first() is TripEvent.Start)
            assertEquals(1, events.count { it is TripEvent.Stop })
            val index = File(directory, "trips/index.jsonl")
            assertEquals(summary.id, TripSummary.fromJson(JSONObject(index.readLines().single())).id)
            assertFalse(File(directory, "trips/active.json").exists())

            val discarded = CountDownLatch(1)
            trips.onArchiveCompleted = { discarded.countDown() }
            instrumentation.runOnMainSync {
                trips.begin(route, destination, emptyList(), 10.0)
                trips.end(arrived = false)
            }
            assertTrue(discarded.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(summary), trips.history.value)
            assertEquals(1, File(directory, "trips").listFiles().orEmpty().count { it.name.endsWith(".rec.gz") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
