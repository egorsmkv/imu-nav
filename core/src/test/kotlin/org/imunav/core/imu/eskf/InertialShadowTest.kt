package org.imunav.core.imu.eskf

import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.JudgedFix
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.gnss.Verdict
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session-level safety: trust, time alignment, dropouts and recording fidelity. */
class InertialShadowTest {
    private fun fix(timeMs: Long = 1000) = RawFix(FixSource.GPS, 1_700_000_000_000L + timeMs, timeMs, 50.45, 30.52, speedMps = 0f, accuracyM = 5f)

    private fun feed(shadow: InertialShadow, fromMs: Long, untilMs: Long) {
        for (time in fromMs..untilMs step 20) {
            shadow.onSensor(InertialSample(time * 1_000_000, InertialKind.ATTITUDE, Vector3.ZERO, 1.0))
            shadow.onSensor(InertialSample(time * 1_000_000, InertialKind.ACCELEROMETER, Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2)))
            shadow.onSensor(InertialSample(time * 1_000_000, InertialKind.GYROSCOPE, Vector3.ZERO))
        }
    }

    private fun started(): InertialShadow = InertialShadow().also {
        feed(it, 800, 1000)
        it.onGps(JudgedFix(fix(), Verdict.GOOD))
        feed(it, 1020, 1400)
        assertNotNull(it.estimate(1_400_000_000))
    }

    @Test
    fun initializesOnlyWithGoodGpsAndFullFreshSensorSet() {
        for (level in listOf(TrustLevel.BAD, TrustLevel.SUSPECT)) {
            val shadow = InertialShadow()
            feed(shadow, 800, 1000)
            shadow.onGps(JudgedFix(fix(), Verdict(level, emptyList())))
            feed(shadow, 1020, 1400)
            assertNull(shadow.estimate(1_400_000_000))
        }
        for (source in listOf(FixSource.FUSED, FixSource.NET, FixSource.CELL)) {
            val shadow = InertialShadow()
            feed(shadow, 800, 1000)
            shadow.onGps(JudgedFix(fix().copy(source = source), Verdict.GOOD))
            feed(shadow, 1020, 1400)
            assertNull(shadow.estimate(1_400_000_000))
        }
        assertEquals(1, started().acceptedGps)
        val empty = InertialShadow()
        empty.onGps(JudgedFix(fix(), Verdict.GOOD))
        feed(empty, 1100, 1500)
        assertNull(empty.estimate(1_500_000_000), "future orientation cannot initialize a past GPS fix")
    }

    @Test
    fun lateAndDuplicateGpsDoNotReapplyAtCurrentTime() {
        val shadow = started()
        val before = assertNotNull(shadow.estimate(1_400_000_000)).state
        shadow.onGps(JudgedFix(fix(), Verdict.GOOD))
        shadow.onGps(JudgedFix(fix(1100), Verdict.GOOD))
        assertEquals(2, shadow.rejectedInputs)
        assertEquals(before, assertNotNull(shadow.estimate(1_400_000_000)).state)
    }

    @Test
    fun dropoutInvalidatesStateAndFreshGpsIsRequiredToRestart() {
        val shadow = started()
        assertNull(shadow.estimate(3_000_000_000))
        feed(shadow, 3000, 3400)
        assertNull(shadow.estimate(3_400_000_000))
        assertTrue(shadow.resets > 0)
        shadow.onGps(JudgedFix(fix(3400), Verdict.GOOD))
        feed(shadow, 3420, 3800)
        assertNotNull(shadow.estimate(3_800_000_000))
        assertEquals(2, shadow.acceptedGps)
    }

    @Test
    fun missingAccelerometerCannotBeSilentlyHeldAcrossAnOutage() {
        val shadow = started()
        for (time in 1420L..2000L step 20) shadow.onSensor(InertialSample(time * 1_000_000, InertialKind.GYROSCOPE, Vector3.ZERO))
        assertNull(shadow.estimate(2_000_000_000))
        assertTrue(shadow.resets > 0)
    }

    @Test
    fun skewWithinReorderingWindowMatchesChronologicalDelivery() {
        val chronological = started()
        val reordered = started()
        feed(chronological, 1420, 1500)
        chronological.onGps(JudgedFix(fix(1500), Verdict.GOOD))
        feed(chronological, 1520, 1800)
        feed(reordered, 1420, 1700)
        reordered.onGps(JudgedFix(fix(1500), Verdict.GOOD))
        feed(reordered, 1720, 1800)
        assertEquals(chronological.acceptedGps, reordered.acceptedGps)
        assertEquals(chronological.estimate(1_800_000_000), reordered.estimate(1_800_000_000))
    }

    @Test
    fun rawSensorNanosecondsAndTinyBiasesRoundTripWithoutRounding() {
        for (kind in InertialKind.entries) {
            val input = TripEvent.Inertial(1234, InertialSample(1_234_567_890L, kind, Vector3(0.000000123456789, -9.806651234567, 0.25), 0.987654321012345))
            assertEquals(input, TripFormat.decode(TripFormat.encode(input)))
        }
        assertNull(TripFormat.decode("U,123,999,GYROSCOPE,1,2"))
        assertNull(TripFormat.decode("U,123,999,UNKNOWN,1,2,3,0"))
    }

    @Test
    fun hubRecordsBeforeDeliveringAndShadowIsAbsentByDefault() {
        val hub = PositioningHub()
        assertNull(hub.inertialObserver)
        val events = mutableListOf<TripEvent>()
        hub.recorder = { events += it }
        val sample = InertialSample(123_456_789L, InertialKind.GYROSCOPE, Vector3.ZERO)
        hub.inertialObserver = { assertEquals(TripEvent.Inertial(124, sample), events.single()) }
        hub.onInertial(sample, 124)
        assertEquals(1, events.size)
    }
}
