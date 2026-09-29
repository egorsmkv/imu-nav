package org.imunav.core

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GnssSnapshot
import org.imunav.core.gnss.JamDetector
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustClassifier
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.MotionDetector
import org.imunav.core.speed.NetSpeedEstimator
import org.imunav.core.speed.SpeedEstimate
import org.imunav.core.speed.SpeedFusion
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComponentsTest {
    private val kyiv = GeoPoint(50.45, 30.52)

    private fun gps(t: Long, lat: Double, lon: Double, speed: Float = 10f, acc: Float = 5f, mock: Boolean = false) =
        RawFix(FixSource.GPS, 1_700_000_000_000 + t, t, lat, lon, 150.0, speed, 0f, acc, 3f, null, mock)

    private val healthyGnss = GnssSnapshot(20, 12, 35f, 5f, 30f, -2f, 0, 0)

    @Test
    fun cleanFixIsGood() {
        val c = TrustClassifier(area = ServiceArea.UKRAINE_COARSE)
        val f1 = gps(1000, kyiv.lat, kyiv.lon)
        val v1 = c.evaluate(f1, null, null, healthyGnss.copy(elapsedMs = 900), false, null, f1.timeMs)
        assertEquals(TrustLevel.GOOD, v1.level, v1.reasons.toString())
    }

    @Test
    fun mockAndOutsideAreaAreBad() {
        val c = TrustClassifier(area = ServiceArea.UKRAINE_COARSE)
        val mock = gps(1000, kyiv.lat, kyiv.lon, mock = true)
        assertEquals(TrustLevel.BAD, c.evaluate(mock, null, null, GnssSnapshot(), false, null, mock.timeMs).level)
        val moscow = gps(2000, 55.75, 37.62)
        val v = c.evaluate(moscow, null, null, GnssSnapshot(), false, null, moscow.timeMs)
        assertEquals(TrustLevel.BAD, v.level)
        assertTrue("outside_area" in v.reasons)
    }

    @Test
    fun teleportIsBadJump() {
        val c = TrustClassifier()
        val a = gps(1000, kyiv.lat, kyiv.lon)
        c.evaluate(a, null, null, GnssSnapshot(), false, null, a.timeMs)
        val b = gps(2000, kyiv.lat + 0.1, kyiv.lon) // ~11 km in 1 s
        val v = c.evaluate(b, a, null, GnssSnapshot(), false, null, b.timeMs)
        assertEquals(TrustLevel.BAD, v.level)
        assertTrue(v.reasons.any { it.startsWith("jump=") })
    }

    @Test
    fun flatCn0IsSuspect() {
        val c = TrustClassifier()
        val f = gps(1000, kyiv.lat, kyiv.lon)
        val v = c.evaluate(f, null, null, healthyGnss.copy(cn0SpreadUsed = 0.5f, elapsedMs = 900), false, null, f.timeMs)
        assertEquals(TrustLevel.SUSPECT, v.level)
        assertTrue("cn0_flat" in v.reasons)
    }

    @Test
    fun hardJamWithoutCorroborationIsBad() {
        val c = TrustClassifier()
        val f = gps(1000, kyiv.lat, kyiv.lon)
        val v = c.evaluate(f, null, null, healthyGnss.copy(agcDb = -20f, elapsedMs = 900), true, null, f.timeMs)
        assertEquals(TrustLevel.BAD, v.level)
        assertTrue("jam" in v.reasons)
    }

    @Test
    fun jamDetectorHysteresis() {
        val j = JamDetector()
        assertTrue(j.update(-14f, 0))
        assertTrue(j.jammed)
        assertFalse(j.update(-5f, 1000))
        assertFalse(j.update(-5f, 10_000))
        assertTrue(j.jammed)
        assertTrue(j.update(-5f, 16_500))
        assertFalse(j.jammed)
    }

    @Test
    fun motionDetectorStopsAndResumes() {
        val m = MotionDetector { Tuning.DEFAULT }
        val rnd = Random(1)
        var t = 0L
        fun feed(ms: Long, accAmp: Double, gyroAmp: Double) {
            val end = t + ms
            while (t < end) {
                val a = (accAmp * (0.5 + rnd.nextDouble())).toFloat()
                val g = (gyroAmp * (0.5 + rnd.nextDouble())).toFloat()
                m.add(ImuSample(t, 0f, 0f, floatArrayOf(a, 0f, 0f), floatArrayOf(g, 0f, 0f)))
                t += 20
            }
        }
        feed(3000, 1.2, 0.2)
        assertFalse(m.stopped)
        assertEquals(1.0, m.motionFactor(t))
        feed(3000, 0.05, 0.005)
        assertTrue(m.stopped)
        assertEquals(0.0, m.motionFactor(t))
        feed(1500, 1.2, 0.2)
        assertFalse(m.stopped)
        val f = m.motionFactor(t)!!
        assertTrue(f in 0.15..0.6, "ramp factor $f")
    }

    @Test
    fun netSpeedRegressionRecoversSlope() {
        val e = NetSpeedEstimator()
        val rnd = Random(7)
        for (i in 0..20) {
            val t = i * 3000L
            e.add(12.0 * t / 1000.0 + rnd.nextDouble(-30.0, 30.0), 40.0, t)
        }
        val est = assertNotNull(e.estimate(60_000))
        assertEquals(12.0, est.speedMps, 1.5)
    }

    @Test
    fun speedFusionWeighsBySigma() {
        val onlyPrior = SpeedFusion.fuse(null, 0, 10.0, null)
        assertEquals(10.0, onlyPrior, 1e-9)
        val freshGps = SpeedFusion.fuse(20.0, 0, 10.0, null)
        assertTrue(freshGps > 18.0, "fresh GPS dominates: $freshGps")
        val withNet = SpeedFusion.fuse(null, 0, 10.0, SpeedEstimate(14.0, 0.5, 10, 60.0))
        assertTrue(withNet > 13.5, "tight network estimate dominates: $withNet")
    }

    @Test
    fun geoBasics() {
        val a = GeoPoint(50.0, 30.0)
        val north = GeoPoint(50.001, 30.0)
        val east = GeoPoint(50.0, 30.001)
        assertEquals(0.0, Geo.bearing(a, north), 0.5)
        assertEquals(90.0, Geo.bearing(a, east), 0.5)
        assertEquals(111.2, Geo.distance(a, north), 1.0)
        assertEquals(-90.0, Geo.angleDiff(90.0, 0.0), 1e-9)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
    }
}
