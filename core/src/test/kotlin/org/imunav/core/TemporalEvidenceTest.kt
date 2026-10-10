package org.imunav.core

import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.JamDetector
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.Pedometer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Invalid clocks and future evidence must not determine a later valid navigation decision. */
class TemporalEvidenceTest {
    @Test
    fun rejectedGpsCannotCalibrateGyroscopeBias() {
        val epoch = 1_700_000_000_000L
        var wall = epoch
        val hub = PositioningHub(wallClock = { wall })
        for (time in 0L..12_000L step 100) {
            hub.gyroBias.addYawRate(time, 1.0)
            if (time % 1000L == 0L) {
                wall = epoch + time
                val fix = RawFix(FixSource.GPS, wall, time, 50.45, 30.52, speedMps = 0f, accuracyM = 5f, isMock = true)
                assertEquals(TrustLevel.BAD, hub.onFix(fix)?.level)
            }
        }
        assertEquals(0.0, hub.gyroBias.biasDegS)
    }

    @Test
    fun clockSkewCannotPoisonGpsRecovery() {
        val epoch = 1_700_000_000_000L
        var wall = epoch + 1000
        val hub = PositioningHub(wallClock = { wall })
        fun fix(time: Long) = RawFix(FixSource.GPS, epoch + time, time, 50.45, 30.52, speedMps = 0f, accuracyM = 5f)
        assertEquals(TrustLevel.GOOD, hub.onFix(fix(1000))?.level)
        assertEquals(TrustLevel.BAD, hub.onFix(fix(1_000_000))?.level)
        wall = epoch + 2000
        assertEquals(TrustLevel.GOOD, hub.onFix(fix(2000))?.level)
        assertEquals(2000L, hub.lastGood?.elapsedMs)
    }

    @Test
    fun futureStepsCannotSupplyCadenceOrCalibrateStride() {
        val pedometer = Pedometer()
        for (time in 1000L..6000L step 500) pedometer.onStep(time)
        val stride = pedometer.strideM
        assertEquals(0.0, pedometer.cadence(500))
        pedometer.learnStride(2.0, 500)
        assertEquals(stride, pedometer.strideM)
        assertEquals(2.0, pedometer.cadence(3000))
        assertEquals(2.0, pedometer.cadence(6000))
        assertEquals(0.0, pedometer.cadence(9000))
    }

    @Test
    fun staleAgcCannotShortenRecoveryHold() {
        val detector = JamDetector()
        assertTrue(detector.update(-20f, 10_000))
        assertFalse(detector.update(-5f, 1000))
        assertFalse(detector.update(-5f, 20_000))
        assertTrue(detector.jammed)
        assertTrue(detector.update(-5f, 35_000))
        assertFalse(detector.jammed)
    }

    @Test
    fun staleAgcCannotReplaceReceiverEvidence() {
        val hub = PositioningHub()
        hub.onAgc(-20f, 10_000)
        hub.onAgc(-5f, 1000)
        assertEquals(-20f, hub.gnss.agcDb)
        assertTrue(hub.jammed)
    }
}
