package org.imunav.core

import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GnssSnapshot
import org.imunav.core.gnss.JamDetector
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustClassifier
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.NetSample
import org.imunav.core.nav.NetworkTracker
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic regressions for the same malformed inputs rejected by the live Rust core. */
class PositioningIngressTest {
    @Test
    fun overflowingClockDifferenceCannotBecomeGood() {
        for ((time, now) in listOf(Long.MIN_VALUE to 0L, Long.MIN_VALUE to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE)) {
            val fix = gps().copy(timeMs = time)
            val verdict = TrustClassifier().evaluate(fix, null, null, receiver(-2f), false, null, now)
            assertEquals(TrustLevel.BAD, verdict.level)
            assertTrue(verdict.reasons.any { it.startsWith("clock_skew=") })
        }
    }

    @Test
    fun overflowingNetworkAgeCannotConfirmHardJamming() {
        val fix = gps().copy(elapsedMs = Long.MAX_VALUE)
        val network = fix.copy(source = FixSource.NET, elapsedMs = Long.MIN_VALUE)
        val gnss = receiver(-20f).copy(elapsedMs = fix.elapsedMs - 100)
        val verdict = TrustClassifier().evaluate(fix, null, network, gnss, true, null, fix.timeMs)
        assertEquals(TrustLevel.BAD, verdict.level)
        assertTrue("jam" in verdict.reasons)
        assertFalse(TrustClassifier.JAM_STRONG in verdict.reasons)
    }

    @Test
    fun malformedGpsMeasurementsCannotBecomeGood() {
        val valid = gps()
        for (fix in listOf(
            valid.copy(altitudeM = Double.NaN),
            valid.copy(speedMps = Float.NaN),
            valid.copy(bearingDeg = Float.POSITIVE_INFINITY),
            valid.copy(accuracyM = Float.NaN),
            valid.copy(verticalAccuracyM = Float.POSITIVE_INFINITY),
            valid.copy(speedMps = -1f),
            valid.copy(accuracyM = -1f),
            valid.copy(verticalAccuracyM = -1f),
        )) {
            val verdict = TrustClassifier().evaluate(fix, null, null, receiver(-2f), false, null, fix.timeMs)
            assertEquals(TrustLevel.BAD, verdict.level)
            assertTrue("invalid" in verdict.reasons)
        }
    }

    @Test
    fun malformedReceiverMeasurementsCannotBecomeGood() {
        val fix = gps()
        for (gnss in listOf(receiver(Float.NaN), receiver(-2f).copy(meanCn0Used = Float.NaN), receiver(-2f).copy(cn0SpreadUsed = Float.POSITIVE_INFINITY))) {
            val verdict = TrustClassifier().evaluate(fix, null, null, gnss, false, null, fix.timeMs)
            assertEquals(TrustLevel.BAD, verdict.level)
            assertTrue("invalid" in verdict.reasons)
        }
    }

    @Test
    fun futureReceiverHealthCannotDiscreditCurrentGps() {
        val fix = gps()
        val future = receiver(-20f).copy(elapsedMs = fix.elapsedMs + 1, satellitesUsed = 0)
        assertEquals(TrustLevel.GOOD, TrustClassifier().evaluate(fix, null, null, future, false, null, fix.timeMs).level)
    }

    @Test
    fun nonfiniteAgcCannotClearJammingOrStartItsExitHold() {
        for (agc in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val detector = JamDetector()
            assertTrue(detector.update(-14f, 0))
            assertFalse(detector.update(agc, 1_000))
            assertFalse(detector.update(agc, 16_000))
            assertTrue(detector.jammed)
            assertFalse(detector.update(-5f, 20_000))
            assertTrue(detector.update(-5f, 35_000))
            assertFalse(detector.jammed)
        }
    }

    @Test
    fun antipodalJumpRetainsThePhysicalReachabilityCheck() {
        for (latitude in -89..89) {
            val first = gps().copy(lat = latitude.toDouble(), lon = -179.0)
            val second = first.copy(elapsedMs = 2_000, timeMs = first.timeMs + 1_000, lat = -latitude.toDouble(), lon = 1.0)
            val classifier = TrustClassifier()
            assertEquals(TrustLevel.GOOD, classifier.evaluate(first, null, null, receiver(-2f), false, null, first.timeMs).level)
            val verdict = classifier.evaluate(second, first, null, receiver(-2f).copy(elapsedMs = 1_900), false, null, second.timeMs)
            assertEquals(TrustLevel.BAD, verdict.level, "latitude=$latitude")
            assertTrue(verdict.reasons.any { it.startsWith("jump=") })
        }
    }

    @Test
    fun recordingPreservesZeroAccuracyAndCoordinateBoundaries() {
        val tracker = NetworkTracker()
        tracker.record(NetSample(0, 0.0, 0.0, 0.0), -90.0, -180.0)
        tracker.record(NetSample(1_000, 0.0, 0.0, 0.0), 90.0, 180.0)
        assertEquals(2, tracker.recent.size)
        assertEquals(2, tracker.history.size)
        assertTrue(tracker.lastTwoConsistent())
    }

    @Test
    fun malformedRecordsDoNotChangeSamplesOrDuplicateTracking() {
        val valid = NetSample(1_000, 10.0, 20.0, 0.0)
        for ((sample, latitude, longitude) in listOf(
            Triple(valid.copy(elapsedMs = -1), 50.0, 30.0),
            Triple(valid.copy(accM = -1.0), 50.0, 30.0),
            Triple(valid.copy(s = Double.NaN), 50.0, 30.0),
            Triple(valid.copy(accM = Double.POSITIVE_INFINITY), 50.0, 30.0),
            Triple(valid.copy(offsetM = Double.NaN), 50.0, 30.0),
            Triple(valid, 91.0, 30.0),
            Triple(valid, 50.0, 181.0),
            Triple(valid, Double.NaN, 30.0),
        )) {
            val tracker = NetworkTracker()
            tracker.record(sample, latitude, longitude)
            assertTrue(tracker.recent.isEmpty())
            assertTrue(tracker.history.isEmpty())
            assertEquals(null, tracker.speedEstimate(1_000))
            tracker.record(valid, 50.0, 30.0)
            assertEquals(listOf(valid), tracker.recent)
            assertEquals(listOf(valid), tracker.history)
        }
    }

    @Test
    fun staleNetworkFixCannotRewindAnchorOrExpandReachability() {
        val tracker = NetworkTracker()
        assertEquals(NetworkTracker.GateResult.ACCEPTED, tracker.gate(10_000, 0.0, 30.0))
        for (time in listOf(-1L, 0L, 9_999L, 10_000L)) {
            assertEquals(NetworkTracker.GateResult.REJECTED, tracker.gate(time, 50.0, 30.0))
        }
        assertEquals(NetworkTracker.GateResult.REJECTED, tracker.gate(11_000, 400.0, 30.0))
    }

    @Test
    fun staleNetworkFixCannotRestartReanchorConfirmation() {
        val tracker = NetworkTracker()
        assertEquals(NetworkTracker.GateResult.ACCEPTED, tracker.gate(0, 0.0, 30.0))
        assertEquals(NetworkTracker.GateResult.REJECTED, tracker.gate(10_000, 2_000.0, 30.0))
        for (time in listOf(9_000L, 10_000L)) {
            assertEquals(NetworkTracker.GateResult.REJECTED, tracker.gate(time, 0.0, 30.0))
        }
        assertEquals(NetworkTracker.GateResult.REANCHORED, tracker.gate(22_000, 2_100.0, 30.0))
    }

    @Test
    fun invalidNetworkNumbersCannotCreateAnchor() {
        val tracker = NetworkTracker()
        for ((position, accuracy) in listOf(Double.NaN to 30.0, 0.0 to Double.NaN, 0.0 to Double.POSITIVE_INFINITY, 0.0 to -1.0)) {
            assertEquals(NetworkTracker.GateResult.REJECTED, tracker.gate(1_000, position, accuracy))
        }
        assertEquals(NetworkTracker.GateResult.ACCEPTED, tracker.gate(1_000, 0.0, 30.0))
    }

    @Test
    fun malformedNetworkAccuracyCannotConfirmHardJamming() {
        val fix = gps()
        for (accuracy in listOf(-1f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)) {
            val network = fix.copy(source = FixSource.NET, accuracyM = accuracy)
            val verdict = TrustClassifier().evaluate(fix, null, network, receiver(-20f), true, null, fix.timeMs)
            assertEquals(TrustLevel.BAD, verdict.level)
            assertTrue("jam" in verdict.reasons)
            assertFalse(TrustClassifier.JAM_STRONG in verdict.reasons)
        }
    }

    @Test
    fun malformedNetworkCoordinatesCannotDiscreditCleanGps() {
        val fix = gps()
        for ((latitude, longitude) in listOf(91.0 to 30.0, 50.0 to 181.0, Double.NaN to 30.0)) {
            val network = fix.copy(source = FixSource.NET, lat = latitude, lon = longitude)
            assertEquals(TrustLevel.GOOD, TrustClassifier().evaluate(fix, null, network, receiver(-2f), false, null, fix.timeMs).level)
        }
    }

    private fun gps() = RawFix(FixSource.GPS, 1_700_000_001_000, 1_000, 50.45, 30.52, speedMps = 0f, accuracyM = 5f)

    private fun receiver(agc: Float) = GnssSnapshot(
        satellitesVisible = 20,
        satellitesUsed = 12,
        meanCn0Used = 35f,
        cn0SpreadUsed = 5f,
        agcDb = agc,
        elapsedMs = 900,
    )
}
