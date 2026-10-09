package org.imunav.core

import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GnssSnapshot
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustClassifier
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.GyroBiasEstimator
import org.imunav.core.record.TripEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Late inputs must not change the state against which the next observation is judged. */
class GpsImuOrderTest {
    private fun fix(time: Long) = RawFix(FixSource.GPS, 1_700_000_000_000L + time, time, 50.45, 30.52, speedMps = 10f, accuracyM = 5f)

    @Test
    fun gpsRejectionCannotRewindSequenceOrResetFrozenHistory() {
        val classifier = TrustClassifier()
        fun judge(fix: RawFix) = classifier.evaluate(fix, null, null, GnssSnapshot(), false, null, fix.timeMs)
        val latest = fix(10_000)
        judge(fix(1000))
        judge(latest)
        assertEquals(listOf("dup_time"), judge(fix(9000).copy(lat = 51.0)).reasons)
        assertEquals(listOf("dup_time"), judge(latest).reasons)
        assertEquals(listOf("dup_time"), judge(fix(11_000).copy(timeMs = latest.timeMs)).reasons)
        val next = judge(fix(31_000))
        assertEquals(TrustLevel.BAD, next.level)
        assertTrue(next.reasons.any { it.startsWith("frozen=") })
        classifier.reset()
        assertEquals(TrustLevel.GOOD, judge(fix(1000)).level)
    }

    @Test
    fun hubRecordsRejectedGpsButDoesNotRewindLatestStateOrNotifyObservers() {
        val latest = fix(10_000)
        val hub = PositioningHub(wallClock = { latest.timeMs })
        val recorded = mutableListOf<TripEvent>()
        var observations = 0
        hub.recorder = { recorded += it }
        hub.judgedFixObserver = { observations++ }
        hub.onFix(latest)
        val judged = hub.lastJudged
        hub.onFix(fix(9000))
        hub.onFix(latest)
        assertEquals(judged, hub.lastJudged)
        assertEquals(latest, hub.lastGood)
        assertEquals(1, observations)
        assertEquals(3, recorded.size)
    }

    @Test
    fun gyroBiasIgnoresDuplicatesLateSamplesAndFutureYaw() {
        val baseline = GyroBiasEstimator()
        val actual = GyroBiasEstimator()
        for (bias in listOf(baseline, actual)) {
            bias.onGpsSpeed(1000, 0f)
            for (time in 1000L..6000L step 100) bias.addYawRate(time, 0.2)
        }
        actual.addYawRate(6000, 2.0)
        actual.addYawRate(5900, 2.0)
        actual.addYawRate(7000, 2.0)
        actual.onGpsSpeed(1000, 10f)
        actual.onGpsSpeed(900, 10f)
        baseline.onGpsSpeed(6000, 0f)
        actual.onGpsSpeed(6000, 0f)
        assertEquals(0.06, baseline.biasDegS, 1e-10)
        assertEquals(baseline.biasDegS, actual.biasDegS, 1e-10)
    }
}
