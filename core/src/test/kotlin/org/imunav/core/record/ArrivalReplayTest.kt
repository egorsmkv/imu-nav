package org.imunav.core.record

import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Arrival clocks must survive recording and remain distinct from sensor measurement clocks. */
class ArrivalReplayTest {
    private fun roundTrip(events: List<Pair<Long, TripEvent>>): List<TripEvent> {
        val bytes = ByteArrayOutputStream()
        TripRecorder(bytes).use { recorder -> events.forEach { (time, event) -> recorder.record(event, time) } }
        return TripFormat.read(bytes.toByteArray().inputStream())
    }

    private fun gps(time: Long) = TripEvent.Fix(
        RawFix(FixSource.GPS, 1_700_000_000_000L + time, time, 50.45, 30.52, speedMps = 0f, accuracyM = 5f),
    )

    @Test
    fun replayScheduleMatchesLiveTrustDecisionsForLateGps() {
        val inputs = listOf(2000L to gps(2000), 2100L to gps(1000), 2200L to gps(2000), 3000L to gps(3000))
        var now = 0L
        fun hub() = PositioningHub(wallClock = { 1_700_000_000_000L + now })
        val live = hub()
        val expected = inputs.map { (arrival, event) ->
            now = arrival
            live.onFix(event.fix)?.level
        }
        assertEquals(listOf(TrustLevel.GOOD, TrustLevel.BAD, TrustLevel.BAD, TrustLevel.GOOD), expected)
        val replay = hub()
        val decoded = roundTrip(inputs)
        val actual = TripTimeline.schedule(decoded).map { entry ->
            now = entry.elapsedMs
            replay.onFix((entry.event as TripEvent.Fix).fix)?.level
        }
        assertEquals(expected, actual)
        assertEquals(inputs.map { it.first }, decoded.map { it.arrivalElapsedMs })
        assertEquals(listOf(2000L, 1000L, 2000L, 3000L), decoded.map { it.elapsedMs })
    }

    @Test
    fun legacySortingAndEqualTimeOrderRemainUnchanged() {
        val events = listOf(TripEvent.Stop(20), TripEvent.StepTaken(10), TripEvent.Agc(10, -2f))
        assertEquals(listOf(events[1], events[2], events[0]), TripTimeline.schedule(events).map { it.event })
        assertEquals(events, TripTimeline.schedule(events, legacyFileOrder = true).map { it.event })
    }

    @Test
    fun metadataIsConsumedByMalformedEventsAndMixedFilesKeepOrder() {
        val events = TripFormat.read("P,100\n# arrival_ms=120\nUNKNOWN,1\nX,110\n# arrival_ms=130\nP,90\n".byteInputStream())
        assertEquals(listOf(null, null, 130L), events.map { it.arrivalElapsedMs })
        assertEquals(listOf(100L, 110L, 130L), TripTimeline.schedule(events).map { it.elapsedMs })
        assertEquals(listOf(100L, 110L, 90L), TripTimeline.schedule(events).map { it.event.elapsedMs })
    }

    @Test
    fun arrivalIsCapturedBeforeIoQueueAndSurvivesRepair() {
        val file = File.createTempFile("arrival-replay", ".rec.gz")
        try {
            val pending = ArrayDeque<Runnable>()
            var clock = 2000L
            val session = RecordingSession(file, Executor { pending.addLast(it) }, false, arrivalClock = { clock }, onFailure = { throw it })
            session.record(gps(1000))
            clock = 9000
            session.finish()
            while (pending.isNotEmpty()) pending.removeFirst().run()
            assertEquals(2000L, TripFormat.read(file).single().arrivalElapsedMs)
            TripFormat.repair(file)
            assertEquals(2000L, TripFormat.read(file).single().arrivalElapsedMs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun delayedVehicleSpeedDoesNotAffectNavigationBeforeArrival() {
        val route = Route(listOf(GeoPoint(50.45, 30.52), GeoPoint(50.48, 30.52)), emptyList(), 200.0)
        val inputs = listOf(
            1000L to TripEvent.Start(1000, route.geometry.last(), emptyList(), 5.0),
            1000L to TripEvent.RouteSet(1000, route),
            1000L to gps(1000),
            2000L to gps(2000),
            3000L to gps(3000),
            3200L to TripEvent.VehicleSpeed(1200, 0f),
            4000L to gps(4000),
            5000L to TripEvent.Stop(5000),
        )
        val recorded = roundTrip(inputs)
        val result = TripReplayer().replay(recorded, hideGpsAfterS = 0.0)
        val baseline = TripReplayer().replay(recorded.filterNot { it is TripEvent.VehicleSpeed }, hideGpsAfterS = 0.0)
        assertTrue(result.samples.isNotEmpty())
        assertEquals(baseline.samples.filter { it.elapsedMs < 3200 }, result.samples.filter { it.elapsedMs < 3200 })
        assertEquals(PositionSource.DR_STOPPED, result.samples.single { it.elapsedMs == 3500L }.source)
        assertEquals(result, TripReplayer().replay(recorded, hideGpsAfterS = 0.0))
    }
}
