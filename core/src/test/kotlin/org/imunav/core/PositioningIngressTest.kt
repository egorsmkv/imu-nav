package org.imunav.core

import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.GnssSnapshot
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
