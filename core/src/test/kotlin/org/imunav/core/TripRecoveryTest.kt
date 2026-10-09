package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripRecorder
import org.imunav.core.record.TripReplayer
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic corrupt recordings exercise recovery without private trip data. */
class TripRecoveryTest {
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    @Test
    fun everyTruncatedFixedHeaderReturnsNoEvents() {
        val recording = gzip("X,123\n")
        for (size in 0 until 10) {
            assertEquals(emptyList(), TripFormat.read(recording.copyOf(size).inputStream()), "header bytes=$size")
        }
    }

    @Test
    fun missingTrailerRetainsCompleteEventsButDropsParseablePartialLine() {
        val recording = gzip("P,10\nX,12")
        val truncated = recording.copyOf(recording.size - 8)
        assertEquals(listOf(TripEvent.StepTaken(10)), TripFormat.read(truncated.inputStream()))
        assertEquals(listOf(TripEvent.StepTaken(10), TripEvent.Stop(12)), TripFormat.read(recording.inputStream()))
    }

    @Test
    fun allCutsRecoverOnlyAnOrderedPrefixOfWrittenEvents() {
        val expected = (1L..12L).map { TripEvent.StepTaken(it * 111) }
        val recording = gzip(expected.joinToString("") { "${TripFormat.encode(it)}\n" })
        for (size in recording.indices) {
            val recovered = TripFormat.read(recording.copyOf(size).inputStream())
            assertEquals(expected.take(recovered.size), recovered, "cut=$size")
        }
        assertEquals(expected, TripFormat.read(recording.inputStream()))
    }

    @Test
    fun malformedAndUnknownLinesDoNotHideLaterEvents() {
        val text = "# version\nP,1\nF,2,GPS,broken\nFUTURE,3,payload\n\nX,4"
        assertEquals(listOf(TripEvent.StepTaken(1), TripEvent.Stop(4)), TripFormat.read(text.byteInputStream()))
    }

    @Test
    fun nonFinitePayloadsAndFloatOverflowAreRejected() {
        for (number in listOf("NaN", "Infinity", "-Infinity", "1e999")) {
            val lines = listOf(
                "V,1,$number",
                "B,1,$number",
                "A,1,$number",
                "I,1,$number,,,,,,,",
                "F,1,GPS,123,$number,30,,,,,,,0",
                "D,1,50,30,$number",
                "E,1,50,30,$number,10,GPS",
                "R,1,10|50:30;50.01:$number|||||",
            )
            for (line in lines) {
                assertNull(TripFormat.decode(line), line)
            }
        }
        assertNull(TripFormat.decode("V,1,1e100"), "finite Double can overflow Float")
        assertNotNull(TripFormat.decode("A,1,"), "empty optional values remain supported")
    }

    @Test
    fun partialVectorsAndUnpairedWaypointsAreRejected() {
        assertNull(TripFormat.decode("I,1,,, ,1,2,,,"))
        assertNull(TripFormat.decode("I,1,,,,1,2,,,"), "missing x must not hide y and z")
        assertNull(TripFormat.decode("D,1,50,30,5,51"))
        assertNotNull(TripFormat.decode("I,1,,,,,,,,"))
        assertNotNull(TripFormat.decode("D,1,50,30,5,51,31"))
    }

    @Test
    fun repairThenAppendPreservesRecoveredPrefix() {
        val file = File.createTempFile("trip-recovery", ".rec.gz")
        try {
            val recording = gzip("P,10\nX,12")
            file.writeBytes(recording.copyOf(recording.size - 8))
            TripFormat.repair(file)
            TripRecorder.open(file, append = true).use { it.record(TripEvent.Stop(20)) }
            assertEquals(listOf(TripEvent.StepTaken(10), TripEvent.Stop(20)), TripFormat.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun repeatedReplayPreservesOrderOfEqualTimestampEvents() {
        val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.01, 30.0)), emptyList(), 100.0)
        val start = TripEvent.Start(100, route.geometry.last(), emptyList(), 5.0)
        val mode = TripEvent.Mode(100, TravelMode.FOOT)
        val routeSet = TripEvent.RouteSet(100, route)
        // Deliberately unsorted timestamps: the equal-time group must retain file order.
        val events = listOf(TripEvent.Stop(1100), start, mode, routeSet)
        val replayer = TripReplayer()
        val first = replayer.replay(events)
        assertTrue(first.log.any { it.startsWith("nav_start ") && it.endsWith("mode=FOOT") })
        assertEquals(first, replayer.replay(events), "a reused replayer must start with fresh state")
        val reversed = replayer.replay(listOf(mode, start, routeSet, TripEvent.Stop(1100)))
        assertTrue(reversed.log.any { it.startsWith("nav_start ") && it.endsWith("mode=CAR") }, "Start resets the legacy default mode")
    }
}
