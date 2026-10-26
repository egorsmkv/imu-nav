package org.imunav.app.trips

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.route.ManeuverType
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A trip is recorded, survives a "process death" through its snapshot, and lands in the history. */
@RunWith(RobolectricTestRunner::class)
class TripManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))
    private val route = Route(
        (0..2000 step 20).map { projection.toGeo(0.0, it.toDouble()) },
        listOf(Step(ManeuverType.DEPART, null, "Khreshchatyk", 2000.0, 150.0, 0), Step(ManeuverType.ARRIVE, null, "", 0.0, 0.0, 100)),
        150.0,
    )
    private val destination = route.geometry.last()
    private val stop = projection.toGeo(0.0, 900.0)
    private val log = ArrayList<String>()
    private lateinit var io: ExecutorService

    @Before
    fun clean() {
        File(context.filesDir, "trips").deleteRecursively()
        io = Executors.newSingleThreadExecutor()
    }

    @After
    fun shutdown() = io.shutdownNow().let { }

    /** Everything queued on the manager's I/O worker so far has run. */
    private fun drain() {
        repeat(3) { io.submit {}.get() }
        ShadowLooper.idleMainLooper()
    }

    private fun manager(hub: PositioningHub, engine: NavigationEngine) = TripManager(context, hub, engine, { log += it }, io)

    private fun engine() = NavigationEngine(listener = object : NavListener {})

    @Test
    fun recordedTripKeepsEveryInputAndManagesHistory() {
        val hub = PositioningHub()
        val engine = engine()
        val trips = manager(hub, engine)
        val started = SystemClock.elapsedRealtime()
        engine.start(route, destination, listOf(stop), started)
        trips.begin(route, destination, listOf(stop), startAccuracyM = 50.0, mode = TravelMode.CAR)
        assertTrue(trips.active)
        assertEquals(RecordingStatus.RECORDING, trips.recordingStatus.value)

        // Inputs of all kinds reach the recording.
        hub.onFix(RawFix(FixSource.GPS, System.currentTimeMillis(), started + 100, 50.45, 30.52, accuracyM = 5f, speedMps = 10f))
        trips.onStep(started + 150)
        trips.onVehicleSpeed(36, started + 200)
        trips.onPressure(1013.2f, started + 250)
        trips.onImu(ImuSample(started + 300, 10f, 0.5f, floatArrayOf(0f, 0.1f, 0f), floatArrayOf(0f, 0f, 0.01f)))
        trips.onManualPosition(stop, 50.0, started + 350)
        trips.onRoute(route)
        trips.onTick(started + 500)
        trips.onTick(started + 1500)
        trips.end(arrived = true, keepShortTrip = true)
        drain()

        val trip = trips.history.value.single()
        assertTrue(trip.arrived)
        assertEquals(1, trip.reroutes)
        assertEquals(TravelMode.CAR, trip.mode)
        val events = TripFormat.read(trips.recordingFile(trip))
        for (kind in listOf(
            TripEvent.Fix::class, TripEvent.StepTaken::class, TripEvent.VehicleSpeed::class, TripEvent.Pressure::class, TripEvent.Imu::class,
            TripEvent.ManualPosition::class, TripEvent.RouteSet::class, TripEvent.Estimate::class, TripEvent.Stop::class,
        )) {
            assertTrue("$kind recorded", events.any { kind.isInstance(it) })
        }
        val tracks = extractTracks(trips.recordingFile(trip), ServiceArea.EVERYWHERE)
        assertTrue(tracks.engine.isNotEmpty())
        assertEquals(1, trips.totals().trips)

        // Editing the history replaces its index file by renaming, which replaces an existing file
        // on Android and Linux but not on a Windows development host.
        assumeTrue("POSIX rename semantics", File.separatorChar == '/')
        trips.setMatchedLength(trip, 1234.0)
        drain()
        assertEquals(1234.0, checkNotNull(trips.history.value.single().matchedLengthM), 1e-9)
        assertEquals(1, trips.totals().trips)
        trips.delete(trip)
        drain()
        assertTrue(trips.history.value.isEmpty())
        assertFalse(trips.recordingFile(trip).exists())
    }

    @Test
    fun activeTripIsRestoredAfterTheProcessDies() {
        val engine = engine()
        val trips = manager(PositioningHub(), engine)
        engine.start(route, destination, listOf(stop), SystemClock.elapsedRealtime())
        trips.begin(route, destination, listOf(stop), startAccuracyM = 50.0)
        drain()

        // The app is killed: a new manager finds the snapshot and continues the same trip.
        val restoredEngine = engine()
        val again = manager(PositioningHub(), restoredEngine)
        val saved = checkNotNull(runBlocking { again.prepareRestore() })
        assertEquals(listOf(stop), saved.waypoints)
        assertEquals(destination.lat, checkNotNull(saved.summary.destination).lat, 1e-6)
        assertTrue(again.restore(saved))
        assertTrue(restoredEngine.state.active)
        assertFalse("a running trip cannot be restored twice", again.restore(saved))
        assertTrue(log.any { it.startsWith("trip_restored") })

        again.end(arrived = true, keepShortTrip = true)
        drain()
        assertTrue(again.history.value.single().arrived)
        assertNull("the snapshot is removed once the trip ends", runBlocking { again.prepareRestore() })
    }

    @Test
    fun accidentalStartWithoutMovementIsNotKept() {
        val engine = engine()
        val trips = manager(PositioningHub(), engine)
        engine.start(route, destination, emptyList(), SystemClock.elapsedRealtime())
        trips.begin(route, destination, emptyList(), startAccuracyM = 20.0)
        trips.end(arrived = false)
        drain()
        assertTrue(trips.history.value.isEmpty())
        assertTrue(log.any { it.startsWith("trip_discarded") })
        assertEquals(RecordingStatus.IDLE, trips.recordingStatus.value)
    }

    @Test
    fun damagedOrStaleSnapshotsAreNotOffered() {
        val dir = File(context.filesDir, "trips").apply { mkdirs() }
        val active = File(dir, "active.json")
        val trips = manager(PositioningHub(), engine())
        active.writeText("{ not json")
        assertNull(runBlocking { trips.prepareRestore() })
        // A recording name that tries to leave the trips folder is rejected.
        active.writeText(JSONObject().put("id", "x").put("savedAt", System.currentTimeMillis()).put("recording", "../evil.rec.gz").toString())
        assertNull(runBlocking { trips.prepareRestore() })
    }

    @Test
    fun historySummariesSurviveTheirJsonRoundTrip() {
        val summary = TripSummary(
            "t1", 1_000, 61_000, GeoPoint(50.0, 30.0), true, 1200.0, 60.0, 50.0, 10.0, 100.0, 80.0, 2000.0, 1, "trip-t1.rec.gz",
            matchedLengthM = 1180.0, mode = TravelMode.FOOT, recordingStatus = RecordingStatus.OVERLOAD,
        )
        assertEquals(summary, TripSummary.fromJson(summary.toJson()))
        assertEquals(86.4, summary.avgMovingKmh, 1e-9)
        assertTrue(summary.recordingStatus.partial)
        val legacy = TripSummary.fromJson(JSONObject().put("id", "old").put("start", 1).put("end", 2))
        assertEquals(TravelMode.CAR, legacy.mode)
        assertNull(legacy.destination)
        assertEquals(RecordingStatus.COMPLETE, legacy.recordingStatus)
    }
}
