package org.blinddriver.core

import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.LocalProjection
import org.blinddriver.core.gnss.FixSource
import org.blinddriver.core.gnss.PositioningHub
import org.blinddriver.core.gnss.RawFix
import org.blinddriver.core.imu.ImuSample
import org.blinddriver.core.nav.EnglishPhrases
import org.blinddriver.core.nav.GuidanceState
import org.blinddriver.core.nav.NavListener
import org.blinddriver.core.nav.NavigationEngine
import org.blinddriver.core.nav.PositionSource
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.Step
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives a synthetic car along an L-shaped route (800 m north, right turn, 800 m east). GPS is
 * available for the first 10 s only; afterwards the engine has nothing but IMU and map.
 */
class EngineSimulationTest {
    private val origin = GeoPoint(50.45, 30.52)
    private val proj = LocalProjection(origin)

    private fun lShapedRoute(): Route {
        val pts = ArrayList<GeoPoint>()
        for (y in 0..800 step 20) pts += proj.toGeo(0.0, y.toDouble())
        for (x in 20..800 step 20) pts += proj.toGeo(x.toDouble(), 800.0)
        val steps = listOf(
            Step("depart", null, "Northway", 800.0, 80.0, 0),
            Step("turn", "right", "Eastway", 800.0, 80.0, 40),
            Step("arrive", null, "", 0.0, 0.0, pts.lastIndex),
        )
        // Speed limit 50 everywhere: route prior = 50/3.6 × 0.8 ≈ 11.1 m/s.
        return Route(pts, steps, 160.0, maxspeedKmh = List(pts.size - 1) { 50 })
    }

    private fun truthPoint(d: Double): GeoPoint =
        if (d <= 800) proj.toGeo(0.0, d) else proj.toGeo(minOf(d - 800, 800.0), 800.0)

    private class Result(val state: GuidanceState, val truthD: Double, val logs: List<String>, val spoken: List<String>, val reroutes: Int, val maxSBeforeTurn: Double)

    /**
     * @param speedWithGps true speed while GPS works
     * @param speedBlind true speed after GPS is lost
     * @param turn whether the car actually turns at the junction
     */
    private fun simulate(
        speedWithGps: Double,
        speedBlind: Double,
        turn: Boolean = true,
        tuning: Tuning = Tuning.DEFAULT,
        until: (Double, Long) -> Boolean = { d, t -> d >= 1500 || t >= 200_000 },
    ): Result {
        val logs = ArrayList<String>()
        val spoken = ArrayList<String>()
        var reroutes = 0
        val listener = object : NavListener {
            override fun onLog(message: String) {
                logs += message
            }

            override fun onSay(text: String, urgent: Boolean) {
                spoken += text
            }

            override fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {
                reroutes++
            }
        }
        var wall = 1_700_000_000_000L
        val hub = PositioningHub(wallClock = { wall })
        val engine = NavigationEngine(tuning = { tuning }, listener = listener, phrases = EnglishPhrases)
        val route = lShapedRoute()
        engine.start(route, route.geometry.last(), nowMs = 0)

        val rnd = Random(42)
        var d = 0.0
        var t = 0L
        var maxSBeforeTurn = 0.0
        val gpsCutoffMs = 10_000L
        val turnStart = 790.0
        val turnEnd = 815.0
        while (!until(d, t)) {
            val v = if (t <= gpsCutoffMs) speedWithGps else speedBlind
            for (k in 0 until 25) { // 50 Hz IMU, 500 ms engine tick
                t += 20
                wall += 20
                d += v * 0.02
                val yaw = if (turn && d in turnStart..turnEnd) (90.0 / ((turnEnd - turnStart) / v)).toFloat() else 0f
                val acc = (1.0 + rnd.nextDouble()).toFloat()
                engine.onImu(ImuSample(t, null, yaw, floatArrayOf(acc, 0f, 0f), floatArrayOf(0.2f, 0f, 0f)))
            }
            if (t <= gpsCutoffMs && t % 1000 == 0L) {
                val p = truthPoint(d)
                hub.onFix(RawFix(FixSource.GPS, wall, t, p.lat, p.lon, 150.0, v.toFloat(), 0f, 4f, 3f, null, false))
            }
            engine.tick(t, hub.snapshot(t))
            if (d < turnStart) maxSBeforeTurn = maxOf(maxSBeforeTurn, engine.state.s)
        }
        return Result(engine.state, d, logs, spoken, reroutes, maxSBeforeTurn)
    }

    @Test
    fun markerRunningAheadWaitsAtTurnUntilGyroConfirms() {
        // Engine believes ~11-12 m/s, the car actually slows to 9 m/s after GPS is lost.
        val r = simulate(speedWithGps = 12.0, speedBlind = 9.0, until = { d, _ -> d >= 1200 })
        assertTrue(r.logs.any { it.startsWith("turn_hold") }, "held at the turn: ${r.logs}")
        assertTrue(r.logs.any { it.startsWith("turn_snap") && it.contains("src=hold") }, "gyro confirmed the turn: ${r.logs}")
        assertTrue(r.maxSBeforeTurn < 805.0, "marker never passed the junction before the car turned (max s=${r.maxSBeforeTurn})")
        assertEquals(PositionSource.DR, r.state.source)
        assertEquals("arrive", r.state.nextStep?.type)
        assertTrue(r.spoken.any { it.contains("GPS signal lost") }, "announced GPS loss: ${r.spoken}")
        // After the snap, along-track error only grows with the ~25 % speed misestimate.
        val err = abs(r.state.s - r.truthD)
        assertTrue(err < 200.0, "final error $err m")
    }

    @Test
    fun laggingMarkerIsCorrectedByGyroTurnMatching() {
        // The car is faster than the engine believes: the turn happens before the marker gets there.
        val r = simulate(speedWithGps = 12.0, speedBlind = 14.0, until = { d, _ -> d >= 900 })
        val snap = r.logs.firstOrNull { it.startsWith("turn_snap") && it.contains("src=gyro") }
        assertTrue(snap != null, "gyro turn matching snapped forward: ${r.logs}")
        val err = abs(r.state.s - r.truthD)
        assertTrue(err < 40.0, "error right after the turn $err m (s=${r.state.s}, truth=${r.truthD})")
    }

    @Test
    fun missedTurnTriggersRerouteCountdown() {
        val r = simulate(
            speedWithGps = 12.0,
            speedBlind = 12.0,
            turn = false,
            tuning = Tuning(missedTurnEnabled = true),
            until = { _, t -> t >= 150_000 },
        )
        assertTrue(r.logs.any { it.startsWith("blind_deviation_missed_turn") }, "missed turn detected: ${r.logs}")
        assertTrue(r.spoken.any { it.contains("missed the turn") })
        assertTrue(r.reroutes >= 1, "auto reroute after the countdown")
    }

    @Test
    fun uncertaintyStartsFromCoarseStartFix() {
        val engine = NavigationEngine(listener = object : NavListener {})
        val route = lShapedRoute()
        engine.start(route, route.geometry.last(), nowMs = 0, startAccuracyM = 1400.0)
        val hub = PositioningHub(wallClock = { 0L })
        var t = 0L
        repeat(20) {
            t += 500
            engine.tick(t, hub.snapshot(t))
        }
        assertTrue(engine.state.uncertaintyM >= 1400.0, "uncertainty ${engine.state.uncertaintyM} must reflect the ±1400 m start")

        // Without a coarse start the floor is the usual 30 m.
        val precise = NavigationEngine(listener = object : NavListener {})
        precise.start(route, route.geometry.last(), nowMs = 0, startAccuracyM = 5.0)
        precise.tick(500, hub.snapshot(500))
        assertTrue(precise.state.uncertaintyM < 100.0)
    }
}
