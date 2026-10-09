package org.imunav.replay

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripRecorder
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic inputs have known motion; they demonstrate regressions, not real-drive accuracy. */
class NativeComparisonTest {
    @Test
    fun comparisonUsesArrivalClockForDelayedInputs() {
        val events = TripFormat.read(
            "# arrival_ms=2000\nP,2000\n# arrival_ms=2100\nP,1000\n# arrival_ms=2200\nX,2200\n".byteInputStream(),
        )
        val observed = mutableListOf<Long>()
        NativeComparison().replay(events, onEvent = { observed += it })
        assertEquals(listOf(2000L, 2100L, 2200L), observed)
    }

    @Test
    fun straightDriveReportsPairedErrorsAtGpsTimestamps() {
        val result = NativeComparison().replay(drive(obdScale = 1.0), hideGpsAfterS = 120.0)
        assertTrue(result.blindSamples.size >= 290)
        assertTrue(result.samples.all { (it.elapsedMs - START_MS) % 1000L == 0L })
        assertTrue(result.nativeBlind.p95M < 15, result.summary())
        assertTrue(result.kotlinBlind.p95M < 15, result.summary())
        println("unbiased synthetic OBD\n${result.summary()}")
    }

    @Test
    fun biasedVehicleSpeedBenchmark() {
        val result = NativeComparison().replay(drive(obdScale = 1.05), hideGpsAfterS = 120.0)
        assertTrue(result.blindSamples.size >= 290)
        assertTrue(result.nativeBlind.p95M < 5.0, result.summary())
        println("five-percent high synthetic OBD\n${result.summary()}")
    }

    @Test
    fun noisyLowReadingObdAndBlindStopStartRemainAccurate() {
        val result = NativeComparison().replay(drive(obdScale = 0.95, noisy = true, stopStart = true), hideGpsAfterS = 120.0)
        assertTrue(result.blindSamples.size >= 250)
        assertTrue(result.nativeBlind.p95M < 15.0, result.summary())
        println("noisy five-percent low OBD with blind braking, stop and restart\n${result.summary()}")
    }

    @Test
    fun hiddenReferenceSpeedCannotChangeEitherEstimate() {
        val events = drive(obdScale = 1.0)
        val changed = events.map { event ->
            if (event is TripEvent.Fix && event.elapsedMs >= START_MS + 120_000) {
                TripEvent.Fix(event.fix.copy(speedMps = 14.0f))
            } else {
                event
            }
        }
        val original = NativeComparison().replay(events, 120.0)
        val altered = NativeComparison().replay(changed, 120.0)
        assertTrue(original.blindSamples.isNotEmpty())
        assertEquals(original.samples.map { it.kotlinS to it.nativeS }, altered.samples.map { it.kotlinS to it.nativeS })
    }

    @Test
    fun absentReferenceDoesNotCreateAccuracySamples() {
        val events = drive(obdScale = 1.0).filterNot { it is TripEvent.Fix }
        assertTrue(NativeComparison().replay(events, 0.0).samples.isEmpty())
    }

    @Test
    fun restoredProgressAndUncertaintyInitializeBothEstimatorsWithoutGps() {
        val route = drive(1.0).filterIsInstance<TripEvent.RouteSet>().first().route
        val point = route.pointAt(850.0).point
        for (mode in TravelMode.entries) {
            val events = buildList {
                add(TripEvent.Start(START_MS, route.geometry.last(), emptyList(), 5.0))
                add(TripEvent.RouteSet(START_MS, route))
                add(TripEvent.VehicleSpeed(START_MS + 100, 120f))
                val restoredAt = START_MS + 500
                add(TripEvent.Start(restoredAt, route.geometry.last(), emptyList(), 100.0))
                add(TripEvent.Mode(restoredAt, mode))
                add(TripEvent.RouteSet(restoredAt, route))
                add(TripEvent.Resume(restoredAt, 850.0))
                for (offset in 0L..3000L step 500) {
                    val time = restoredAt + offset
                    // Reference-only: hidden from both estimators for the entire restored segment.
                    add(TripEvent.Fix(RawFix(FixSource.GPS, WALL_MS + time - START_MS, time, point.lat, point.lon, accuracyM = 4f, speedMps = 0f)))
                }
            }
            val result = NativeComparison().replay(events, 0.0)
            assertTrue(result.samples.isNotEmpty())
            val first = result.samples.first()
            assertEquals(850.0, first.kotlinS, 0.01)
            assertEquals(850.0, first.nativeS, 0.01)
            assertTrue(first.nativeSigmaM >= 100.0)
        }
    }

    @Test
    fun commandWritesPairedCsvAndSummaryFromARecording() {
        val directory = Files.createTempDirectory("native-replay-test").toFile()
        try {
            val recording = File(directory, "synthetic.rec.gz")
            TripRecorder(recording.outputStream()).use { recorder -> drive(1.05).forEach(recorder::record) }
            val output = File(directory, "report")
            main(arrayOf(recording.absolutePath, "--compare-native", "--hide-gps-after", "120", "--out", output.absolutePath))
            assertTrue(File(output, "summary.txt").readText().contains("GPS hidden, native:"))
            val csv = File(output, "native-errors-synthetic-hide120s.csv").readLines()
            assertTrue(csv.first().contains("native_error_m"))
            assertTrue(csv.size > 300)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Two minutes of calibration followed by five minutes blind at 15 m/s, with 5 Hz OBD. */
    private fun drive(obdScale: Double, noisy: Boolean = false, stopStart: Boolean = false): List<TripEvent> {
        val projection = LocalProjection(GeoPoint(50.45, 30.52))
        val points = (0..7000 step 20).map { projection.toGeo(0.0, it.toDouble()) }
        val route = Route(points, listOf(Step("depart", null, "test", 7000.0, 467.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex)), 467.0)
        return buildList {
            add(TripEvent.Start(START_MS, points.last(), emptyList(), 5.0))
            add(TripEvent.RouteSet(START_MS, route))
            var distanceM = 0.0
            var previousSpeedMps = 15.0
            for (offsetMs in 200L..420_000L step 200) {
                val elapsedMs = START_MS + offsetMs
                val speedMps = if (stopStart) {
                    when (offsetMs) {
                        in 120_000..150_000 -> (150_000 - offsetMs) / 2000.0
                        in 150_001..180_000 -> 0.0
                        in 180_001..210_000 -> (offsetMs - 180_000) / 2000.0
                        else -> 15.0
                    }
                } else {
                    15.0
                }
                distanceM += (previousSpeedMps + speedMps) * 0.1
                previousSpeedMps = speedMps
                val obdNoise = if (noisy && speedMps > 0.0) (offsetMs / 200 % 3 - 1) * 0.05 else 0.0
                add(TripEvent.VehicleSpeed(elapsedMs, ((speedMps * obdScale + obdNoise) * 3.6).toFloat()))
                if (offsetMs % 1000 == 0L) {
                    val positionNoise = if (noisy) (offsetMs / 1000 % 7 - 3).toDouble() else 0.0
                    val speedNoise = if (noisy && speedMps > 0.0) (offsetMs / 1000 % 3 - 1) * 0.15 else 0.0
                    val point = route.pointAt(distanceM + positionNoise).point
                    val fix = RawFix(
                        FixSource.GPS, WALL_MS + offsetMs, elapsedMs, point.lat, point.lon, 150.0,
                        (speedMps + speedNoise).toFloat(), 0f, 4f, 3f, 0.3f, false,
                    )
                    add(TripEvent.Fix(fix))
                }
            }
            add(TripEvent.Stop(START_MS + 420_001))
        }
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val WALL_MS = 1_700_000_000_000L
    }
}
