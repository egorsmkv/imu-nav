package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.Pedometer
import org.imunav.core.nav.EnglishPhrases
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A pedestrian walks 1 km north at 1.4 m/s (1.9 steps/s, stride 0.74 m). GPS works for the first
 * minute (enough to learn the stride), then disappears; the engine must keep up from steps alone.
 */
class WalkingTest {
    private val proj = LocalProjection(GeoPoint(46.48, 30.73))

    private fun straightRoute(): Route {
        val points = (0..1000 step 10).map { proj.toGeo(0.0, it.toDouble()) }
        val steps = listOf(Step("depart", null, "Deribasivska", 1000.0, 720.0, 0), Step("arrive", null, "", 0.0, 0.0, points.lastIndex))
        return Route(points, steps, 720.0)
    }

    @Test
    fun pedometerMeasuresCadenceAndLearnsStride() {
        val pedometer = Pedometer()
        assertEquals(null, pedometer.speed(0), "no step sensor yet")
        var t = 0L
        repeat(20) {
            t += 526 // 1.9 steps/s
            pedometer.onStep(t)
        }
        val cadence = assertNotNull(pedometer.cadence(t))
        assertTrue(abs(cadence - 1.9) < 0.05, "cadence $cadence")
        repeat(40) { pedometer.learnStride(gpsSpeedMps = 1.4, nowMs = t) }
        assertTrue(abs(pedometer.strideM - 1.4 / 1.9) < 0.02, "stride ${pedometer.strideM}")
        assertEquals(0.0, pedometer.speed(t + 5_000), "standing still after 5 s without steps")
    }

    @Test
    fun deadReckonsFromStepsWithoutGps() {
        val logs = ArrayList<String>()
        val spoken = ArrayList<String>()
        val listener = object : NavListener {
            override fun onLog(message: String) {
                logs += message
            }

            override fun onSay(text: String, urgent: Boolean) {
                spoken += text
            }
        }
        var wall = 1_700_000_000_000L
        val hub = PositioningHub(wallClock = { wall })
        val engine = NavigationEngine(listener = listener, phrases = EnglishPhrases)
        val route = straightRoute()
        engine.start(route, route.geometry.last(), nowMs = 0, mode = TravelMode.FOOT)

        val speed = 1.4
        val stepEveryMs = 526L
        val gpsUntilMs = 60_000L
        val rnd = Random(7)
        var walked = 0.0
        var t = 0L
        var nextStep = stepEveryMs
        while (t < 500_000) {
            for (k in 0 until 25) { // 50 Hz IMU, 500 ms engine tick
                t += 20
                wall += 20
                walked += speed * 0.02
                if (t >= nextStep) {
                    engine.onStep(t)
                    nextStep += stepEveryMs
                }
                // A phone in the hand: lots of motion, random turning that must NOT be read as route turns.
                val shake = (1.5 + rnd.nextDouble()).toFloat()
                val yaw = (rnd.nextDouble() * 60 - 30).toFloat()
                engine.onImu(ImuSample(t, null, yaw, floatArrayOf(shake, 0f, 0f), floatArrayOf(0.6f, 0f, 0f)))
            }
            if (t <= gpsUntilMs && t % 1000 == 0L) {
                val p = proj.toGeo(0.0, walked)
                hub.onFix(RawFix(FixSource.GPS, wall, t, p.lat, p.lon, 100.0, speed.toFloat(), 0f, 5f, 3f, null, false))
            }
            engine.tick(t, hub.snapshot(t))
        }

        val state = engine.state
        assertEquals(TravelMode.FOOT, state.travelMode)
        assertTrue(state.source != PositionSource.GPS, "GPS is gone by now")
        // 440 s blind at 1.4 m/s = 616 m of dead reckoning; allow 5 % error.
        val error = abs(state.s - walked)
        assertTrue(error < 0.05 * (walked - speed * 60), "walked ${walked.toInt()} m, engine ${state.s.toInt()} m")
        assertTrue(logs.none { it.startsWith("turn_snap") || it.startsWith("blind_deviation") }, "no car-only corrections on foot")
        assertTrue(spoken.none { "1 km" in it || "400 m" in it }, "walking prompts use short distances: $spoken")
    }
}
