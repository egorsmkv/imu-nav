package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.nav.ElevationMatcher
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.PositionSource
import org.imunav.core.record.RouteCodec
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A straight 6 km road north. GPS works for the first 10 s (car at 14 m/s); then it is jammed and
 * the car speeds up to 16 m/s without the engine noticing — dead reckoning falls behind by 2 m/s.
 * The barometer (terrain matching) or the car's own speed (OBD-II) must keep it on track.
 */
class TerrainAndVehicleSpeedTest {
    private val proj = LocalProjection(GeoPoint(50.45, 30.52))

    /** Rolling hills: two waves of different lengths, so no stretch looks like another. */
    private fun heightAt(y: Double) = 150.0 + 15.0 * sin(2 * PI * y / 700.0) + 6.0 * sin(2 * PI * y / 230.0)

    private fun route(withElevation: Boolean): Route {
        val pts = (0..6000 step 20).map { proj.toGeo(0.0, it.toDouble()) }
        val steps = listOf(Step("depart", null, "North", 6000.0, 430.0, 0), Step("arrive", null, "", 0.0, 0.0, pts.lastIndex))
        val heights = if (withElevation) DoubleArray(pts.size) { heightAt(it * 20.0) } else null
        return Route(pts, steps, 430.0, maxspeedKmh = List(pts.size - 1) { 50 }, elevationM = heights)
    }

    private class Run(val errorM: Double, val logs: List<String>, val sources: Set<PositionSource>, val uncertaintyM: Double)

    /** [blindS] stays below 120 s: without IMU samples the engine then starts fading its speed out (a test artifact). */
    private fun drive(withElevation: Boolean, barometer: Boolean, obd: Boolean, blindS: Int = 115): Run {
        val logs = ArrayList<String>()
        val engine = NavigationEngine(
            listener = object : NavListener {
                override fun onLog(message: String) {
                    logs += message
                }
            },
        )
        var wall = 1_700_000_000_000L
        val hub = PositioningHub(wallClock = { wall })
        val route = route(withElevation)
        engine.start(route, route.geometry.last(), nowMs = 0)
        val rnd = Random(7)
        val sources = HashSet<PositionSource>()
        var d = 0.0
        var t = 0L
        val gpsUntil = 10_000L
        while (t < gpsUntil + blindS * 1000L) {
            val v = if (t < gpsUntil) 14.0 else 16.0
            repeat(5) {
                // 100 ms steps: barometer and OBD at 10 Hz, engine tick every 500 ms.
                t += 100
                wall += 100
                d += v * 0.1
                if (barometer) {
                    val hPa = 1013.25 * (1.0 - heightAt(d) / 44_330.0).pow(5.255) + rnd.nextDouble(-0.02, 0.02)
                    engine.onPressure(hPa, t)
                }
                if (obd) engine.onVehicleSpeed(v * 3.6, t)
            }
            if (t <= gpsUntil && t % 1000 == 0L) {
                val p = proj.toGeo(0.0, d)
                hub.onFix(RawFix(FixSource.GPS, wall, t, p.lat, p.lon, 150.0, v.toFloat(), 0f, 4f, 3f, null, false))
            }
            engine.tick(t, hub.snapshot(t))
            sources += engine.state.source
        }
        return Run(abs(engine.state.s - d), logs, sources, engine.state.uncertaintyM)
    }

    @Test
    fun deadReckoningAloneFallsBehind() {
        val r = drive(withElevation = true, barometer = false, obd = false)
        assertTrue(r.errorM > 150.0, "control run: speed error accumulates (error ${r.errorM} m)")
    }

    @Test
    fun barometerMatchedToRouteElevationKeepsTheMarkerOnTrack() {
        val r = drive(withElevation = true, barometer = true, obd = false)
        assertTrue(r.logs.any { it.startsWith("terrain_snap") }, "terrain matching corrected the position: ${r.logs.filter { it.startsWith("terrain") }}")
        assertTrue(r.errorM < 60.0, "final along-track error ${r.errorM} m")
    }

    @Test
    fun routeWithoutElevationIgnoresTheBarometer() {
        val r = drive(withElevation = false, barometer = true, obd = false)
        assertTrue(r.logs.none { it.startsWith("terrain") })
        assertTrue(r.errorM > 150.0)
    }

    @Test
    fun flatRoadNeverProducesATerrainFix() {
        // Barometer on, route "has elevation" but it is flat: every position fits, so nothing may be claimed.
        val pts = (0..3000 step 20).map { proj.toGeo(0.0, it.toDouble()) }
        val flat = Route(pts, listOf(Step("depart", null, "", 3000.0, 200.0, 0)), 200.0, elevationM = DoubleArray(pts.size) { 120.0 })
        val matcher = ElevationMatcher()
        var odo = 0.0
        for (i in 0..200) {
            matcher.onPressure(1000.0, i * 1000L)
            odo += 10.0
            matcher.onTravel(odo, i * 1000L)
        }
        assertNull(matcher.match(flat, 1500.0, 500.0, nowMs = 200_000))
    }

    @Test
    fun aConstantGradeCannotConfirmPositionInATruncatedSearchWindow() {
        val points = (0..100).map { GeoPoint(50.0 + it * 0.0001, 30.0) }
        val geometry = Route(points, emptyList(), 100.0)
        val graded = Route(points, emptyList(), 100.0, elevationM = DoubleArray(points.size) { 100.0 + 0.06 * geometry.cumulative[it] })
        val matcher = ElevationMatcher()
        for (index in 0..40) {
            val height = 100.0 + 0.06 * (200.0 + index * 10.0)
            matcher.onPressure(1013.25 * (1 - height / 44_330.0).pow(5.255), index * 1000L)
            matcher.onTravel(index * 10.0, index * 1000L)
        }
        for (center in listOf(240.0, 300.0, 600.0)) {
            assertNull(matcher.match(graded, center, 150.0, 40_000, ElevationMatcher.VEHICLE_SPEED_SCALES))
        }
        assertNull(matcher.match(graded, 600.0, 0.0, 40_000, ElevationMatcher.VEHICLE_SPEED_SCALES))
    }

    @Test
    fun terrainFromAnotherRoadIsNotMistakenForThisOne() {
        // The car is not on this route at all: its barometer follows different hills.
        val route = route(withElevation = true)
        var falseMatches = 0
        for (phase in 0 until 20) {
            val matcher = ElevationMatcher()
            var odo = 0.0
            for (i in 0..150) {
                val otherHeight = 140.0 + 12.0 * sin(2 * PI * odo / 450.0 + phase) + 5.0 * sin(2 * PI * odo / 170.0 + 2 * phase)
                matcher.onPressure(1013.25 * (1.0 - otherHeight / 44_330.0).pow(5.255), i * 1000L)
                matcher.onPressure(1013.25 * (1.0 - otherHeight / 44_330.0).pow(5.255), i * 1000L + 999)
                matcher.onTravel(odo, i * 1000L + 999)
                odo += 10.0
            }
            if (matcher.match(route, 3000.0, 1000.0, nowMs = 150_999) != null) falseMatches++
        }
        assertEquals(0, falseMatches, "confident matches on unrelated terrain")
    }

    @Test
    fun carSpeedFromObdKeepsDeadReckoningAccurate() {
        val r = drive(withElevation = false, barometer = false, obd = true)
        assertTrue(PositionSource.DR_OBD in r.sources, "reported OBD-assisted dead reckoning: ${r.sources}")
        assertTrue(r.logs.any { it.startsWith("vehicle_speed active=true") })
        assertTrue(r.errorM < 25.0, "final along-track error ${r.errorM} m")
        // 1 840 m blind with 2 % drift ≈ 25 + 37 m, instead of 8 % (≈ 170 m).
        assertTrue(r.uncertaintyM < 100.0, "uncertainty ${r.uncertaintyM} m")
    }

    @Test
    fun accelerationWithGpsLatencyCannotBeLearnedAsObdScaleError() {
        val engine = NavigationEngine(listener = object : NavListener {})
        val route = route(false)
        engine.start(route, route.geometry.last(), nowMs = 0)
        val epoch = 1_700_000_000_000L
        var wall = epoch
        val hub = PositioningHub(wallClock = { wall })
        for (time in 100L..10_000L step 100) {
            val seconds = time / 1000.0
            engine.onVehicleSpeed((10.0 + seconds) * 3.6, time)
            wall = epoch + time
            if (time >= 1000 && time % 1000L == 0L) {
                val fixTime = time - 500L
                val fixSeconds = fixTime / 1000.0
                val point = proj.toGeo(0.0, 10 * fixSeconds + fixSeconds * fixSeconds / 2)
                hub.onFix(RawFix(FixSource.GPS, epoch + fixTime, fixTime, point.lat, point.lon, speedMps = (10 + fixSeconds).toFloat(), accuracyM = 4f))
            }
            engine.tick(time, hub.snapshot(time))
        }
        // Fresh wheel speed after GPS expiry exposes the learned multiplier through navigation.
        for (time in 10_100L..16_000L step 100) {
            engine.onVehicleSpeed(72.0, time)
            engine.tick(time, hub.snapshot(time))
        }
        assertEquals(72.0, engine.state.speedKmh.toDouble(), 1e-6)
    }

    @Test
    fun stableContemporaneousSpeedsStillIdentifyObdScale() {
        val engine = NavigationEngine(listener = object : NavListener {})
        val route = route(false)
        engine.start(route, route.geometry.last(), nowMs = 0)
        val epoch = 1_700_000_000_000L
        var wall = epoch
        val hub = PositioningHub(wallClock = { wall })
        for (time in 500L..66_000L step 500) {
            wall = epoch + time
            engine.onVehicleSpeed(79.2, time) // Wheel speed reads 10% high.
            if (time <= 60_000L && time % 1000L == 0L) {
                val point = proj.toGeo(0.0, time * 0.02)
                hub.onFix(RawFix(FixSource.GPS, wall, time, point.lat, point.lon, speedMps = 20f, accuracyM = 4f))
            }
            engine.tick(time, hub.snapshot(time))
        }
        assertEquals(72.0, engine.state.speedKmh.toDouble(), 0.5)
    }

    @Test
    fun periodicTerrainCannotIdentifyWhichRepeatedHillTheCarOccupies() {
        val points = (0..6000 step 20).map { proj.toGeo(0.0, it.toDouble()) }
        fun height(distance: Double) = 100.0 + 15.0 * sin(2 * PI * distance / 400.0)
        val route = Route(points, listOf(Step("depart", null, "", 6000.0, 400.0, 0)), 400.0, elevationM = DoubleArray(points.size) { height(it * 20.0) })
        val matcher = ElevationMatcher()
        for (distance in 0..2000 step 5) {
            val now = distance * 100L
            matcher.onPressure(1013.25 * (1.0 - height(distance.toDouble()) / 44_330.0).pow(5.255), now)
            matcher.onTravel(distance.toDouble(), now)
        }
        assertTrue(matcher.windowM >= 400.0)
        assertNull(matcher.match(route, 2000.0, 800.0, 200_000L, scales = listOf(1.0)))
    }

    @Test
    fun routeElevationSurvivesTheRouteCodec() {
        val original = route(withElevation = true)
        val decoded = RouteCodec.decode(RouteCodec.encode(original))
        val heights = assertNotNull(decoded.elevationM)
        assertEquals(original.geometry.size, heights.size)
        val originalHeights = assertNotNull(original.elevationM)
        assertTrue(originalHeights.indices.all { abs(originalHeights[it] - heights[it]) <= 0.05 })
        assertEquals(heightAt(510.0), assertNotNull(decoded.elevationAt(510.0)), 0.6)
        // Routes without heights (online routing, old recordings) stay without.
        assertNull(RouteCodec.decode(RouteCodec.encode(route(withElevation = false))).elevationM)
    }

    @Test
    fun newRecordingEventsRoundTrip() {
        val speed = TripFormat.decode(TripFormat.encode(TripEvent.VehicleSpeed(1234, 57f)))
        assertEquals(TripEvent.VehicleSpeed(1234, 57f), speed)
        val pressure = TripFormat.decode(TripFormat.encode(TripEvent.Pressure(99, 987.125f)))
        assertEquals(TripEvent.Pressure(99, 987.125f), pressure)
    }
}
