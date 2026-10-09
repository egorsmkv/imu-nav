package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.record.RouteCodec
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripRecorder
import org.imunav.core.record.TripReplayer
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayTest {
    private val proj = LocalProjection(GeoPoint(50.45, 30.52))

    private fun route(): Route {
        val pts = ArrayList<GeoPoint>()
        for (y in 0..800 step 20) pts += proj.toGeo(0.0, y.toDouble())
        for (x in 20..800 step 20) pts += proj.toGeo(x.toDouble(), 800.0)
        val steps = listOf(
            Step("depart", null, "Північна", 800.0, 80.0, 0),
            Step("turn", "right", "Східна, 5|~;", 800.0, 80.0, 40),
            Step("arrive", null, "", 0.0, 0.0, pts.lastIndex),
        )
        return Route(pts, steps, 160.0, maxspeedKmh = List(pts.size - 1) { if (it < 40) 50 else null }, summary = "test")
    }

    private fun truth(d: Double): GeoPoint = if (d <= 800) proj.toGeo(0.0, d) else proj.toGeo(minOf(d - 800, 800.0), 800.0)

    @Test
    fun routeCodecRoundTrip() {
        val r = route()
        val back = RouteCodec.decode(RouteCodec.encode(r))
        assertEquals(r.geometry.size, back.geometry.size)
        assertEquals(r.geometry[37].lat, back.geometry[37].lat, 1e-6)
        assertEquals(r.steps, back.steps, "names with separators survive escaping")
        assertEquals(r.maxspeedKmh, back.maxspeedKmh)
        assertEquals(r.length, back.length, 0.5)
    }

    @Test
    fun recordThenReplayWithGpsHidden() {
        val r = route()
        val buf = ByteArrayOutputStream()
        val rec = TripRecorder(buf)
        val rnd = Random(9)
        val t0 = 1_000_000L
        val wall0 = 1_700_000_000_000L
        rec.record(TripEvent.Start(t0, r.geometry.last(), emptyList(), 5.0))
        rec.record(TripEvent.RouteSet(t0, r))
        var d = 0.0
        var t = t0
        val v = 11.0
        while (d < 1500) {
            t += 20
            d += v * 0.02
            val yaw = if (d in 790.0..815.0) (90.0 / (25.0 / v)).toFloat() else 0f
            rec.record(TripEvent.Imu(ImuSample(t, null, yaw, floatArrayOf((1 + rnd.nextDouble()).toFloat(), 0f, 0f), floatArrayOf(0.2f, 0f, 0f))))
            if ((t - t0) % 1000 == 0L) {
                val p = truth(d)
                rec.record(TripEvent.Fix(RawFix(FixSource.GPS, wall0 + (t - t0), t, p.lat, p.lon, 150.0, v.toFloat(), 0f, 4f, 3f, null, false)))
            }
        }
        rec.record(TripEvent.Stop(t))
        rec.close()

        val events = TripFormat.read(ByteArrayInputStream(buf.toByteArray()))
        assertTrue(events.count { it is TripEvent.Fix } > 100)
        assertTrue(events.first() is TripEvent.Start)

        val asRecorded = TripReplayer().replay(events)
        assertTrue(asRecorded.withGps.count > 50)
        assertTrue(asRecorded.withGps.medianM < 15, "with GPS the engine follows GPS: ${asRecorded.withGps}")

        val blind = TripReplayer().replay(events, hideGpsAfterS = 10.0)
        assertNotNull(blind.blindFromMs)
        assertTrue(blind.blind.count > 100, "blind samples: ${blind.blind}")
        assertTrue(blind.blind.p95M < 120, "dead reckoning along-track error: ${blind.blind}")
        assertTrue(blind.log.any { it.startsWith("turn_snap") }, "turn snapped during replay")
        assertEquals(blind, TripReplayer().replay(events, hideGpsAfterS = 10.0), "identical inputs reproduce samples, tracks, logs and statistics")
        println(blind.summary())
    }

    @Test
    fun resumeEventRoundTripsAndRejectsInvalidProgress() {
        val event = TripEvent.Resume(1234, 850.25)
        assertEquals("Q,1234,850.25", TripFormat.encode(event))
        assertEquals(event, TripFormat.decode(TripFormat.encode(event)))
        assertNull(TripFormat.decode("Q,1234,NaN"))
        assertNull(TripFormat.decode("Q,1234,-5"))
    }

    @Test
    fun restoredTripsWithoutGpsConsumeAlreadyPassedTurns() {
        val route = route()
        for (mode in TravelMode.entries) {
            val events = buildList {
                add(TripEvent.Start(1000, route.geometry.last(), emptyList(), 75.0))
                add(TripEvent.Mode(1000, mode))
                add(TripEvent.RouteSet(1000, route))
                add(TripEvent.Resume(1000, 850.0))
                // A second right turn near the already-driven route turn must not snap backwards.
                for (time in 1020L..4020L step 20) {
                    add(TripEvent.Imu(ImuSample(time, null, 30f, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0.5f))))
                }
                add(TripEvent.Stop(4500))
            }
            val result = TripReplayer().replay(events)
            assertTrue(result.log.contains("nav_resume s=850"), result.log.toString())
            assertTrue(result.log.any { it.contains("mode=$mode") })
            assertTrue(result.log.none { it.startsWith("turn_snap step=1 ") }, result.log.toString())
            assertTrue(result.samples.isEmpty(), "No GPS means no invented accuracy samples")
        }
    }

    @Test
    fun restorationDropsPreKillVehicleSpeedAndRestoresProgress() {
        val route = route()
        val point = route.pointAt(850.0).point
        val events = buildList {
            add(TripEvent.Start(1000, route.geometry.last(), emptyList(), 5.0))
            add(TripEvent.RouteSet(1000, route))
            add(TripEvent.VehicleSpeed(1100, 120f))
            add(TripEvent.Start(1500, route.geometry.last(), emptyList(), 100.0))
            add(TripEvent.RouteSet(1500, route))
            add(TripEvent.Resume(1500, 850.0))
            for (time in 1600L..3600L step 500) {
                add(TripEvent.Fix(RawFix(FixSource.GPS, 1_700_000_000_000L + time, time, point.lat, point.lon, accuracyM = 4f, speedMps = 0f)))
            }
        }
        val result = TripReplayer().replay(events, hideGpsAfterS = 0.0)
        assertTrue(result.samples.isNotEmpty())
        assertEquals(850.0, result.samples.first().engineS, 0.01)
        val fresh = TripReplayer().replay(events.dropWhile { it.elapsedMs < 1500 }, hideGpsAfterS = 0.0)
        assertEquals(fresh.samples, result.samples, "restoration must not retain the old vehicle speed")
        assertTrue(result.samples.all { it.uncertaintyM >= 100.0 })
    }

    @Test
    fun truncatedRecordingIsSalvaged() {
        val f = File.createTempFile("trip", ".rec.gz")
        val rec = TripRecorder(f.outputStream(), flushEveryMs = 0)
        for (i in 0 until 500) rec.record(TripEvent.Agc(i * 10L, -5f))
        // Simulate a kill: no close(), and cut the file mid-stream.
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 3))
        val events = TripFormat.read(f)
        assertTrue(events.size >= 400, "salvaged ${events.size} events")
        TripFormat.repair(f)
        val appended = TripRecorder.open(f, append = true)
        appended.record(TripEvent.Agc(99_999, -1f))
        appended.close()
        val all = TripFormat.read(f)
        assertEquals(99_999, all.last().elapsedMs, "appended after repair")
        f.delete()
    }
}
