package org.imunav.replay

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.eskf.ErrorStateEkf
import org.imunav.core.imu.eskf.InertialKind
import org.imunav.core.imu.eskf.InertialSample
import org.imunav.core.imu.eskf.Vector3
import org.imunav.core.record.ReplayStats
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripReplayer
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Raw recordings evaluate the actual session, including trust, sensor clocks and GPS isolation. */
class InertialComparisonTest {
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))
    private val route = Route(listOf(projection.toGeo(0.0, 0.0), projection.toGeo(0.0, 5000.0)), emptyList(), 500.0)

    private fun recording(): List<TripEvent> = buildList {
        add(TripEvent.Start(1000, route.geometry.last(), emptyList(), 5.0))
        add(TripEvent.Mode(1000, TravelMode.CAR))
        add(TripEvent.RouteSet(1000, route))
        for (time in 1000L..41_000L step 20) {
            add(TripEvent.Inertial(time + 2, InertialSample(time * 1_000_000, InertialKind.ATTITUDE, Vector3.ZERO, 1.0)))
            add(TripEvent.Inertial(time + 2, InertialSample(time * 1_000_000, InertialKind.ACCELEROMETER, Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2))))
            add(TripEvent.Inertial(time + 2, InertialSample(time * 1_000_000, InertialKind.GYROSCOPE, Vector3.ZERO)))
            add(TripEvent.Imu(ImuSample(time + 2, null, 0f, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f))))
            if (time % 1000 == 0L) {
                val point = projection.toGeo(0.0, (time - 1000) / 1000.0 * 10.0)
                add(TripEvent.Fix(RawFix(FixSource.GPS, 1_700_000_000_000L + time, time, point.lat, point.lon, speedMps = 10f, bearingDeg = 0f, accuracyM = 5f)))
            }
        }
        add(TripEvent.Stop(41_010))
    }.map { requireNotNull(TripFormat.decode(TripFormat.encode(it))) }

    @Test
    fun stationarySpecificForceMaintainsKnownVelocityDuringGpsLoss() {
        val result = InertialComparison().replay(recording(), 10.0)
        val blind = result.samples.filter { it.blind }
        assertTrue(blind.size >= 25, result.summary())
        assertTrue(ReplayStats.of(blind.map { it.inertialErrorM }).p95M < 1.0, result.summary())
        assertTrue(result.unpaired > 0, "startup/final reorder-window gaps must be visible")
        assertEquals(0, result.resets)
        assertTrue(result.csv().contains("eskf_sigma_m"))
    }

    @Test
    fun hiddenGpsCannotInitializeOrChangeInertialPosition() {
        val events = recording()
        val allHidden = InertialComparison().replay(events, 0.0)
        assertTrue(allHidden.samples.isEmpty())
        assertTrue(allHidden.unpaired >= 30)
        val original = InertialComparison().replay(events, 10.0).samples.filter { it.blind }.associateBy { it.elapsedMs }
        val modified = events.map {
            if (it is TripEvent.Fix && it.elapsedMs >= 11_000) TripEvent.Fix(it.fix.copy(lat = it.fix.lat + 0.00001)) else it
        }
        val changed = InertialComparison().replay(modified, 10.0).samples.filter { it.blind }
        assertTrue(changed.size >= 25)
        for ((elapsedMs, _, _, _, _, inertialSigmaM, inertialPoint) in changed) {
            assertEquals(original.getValue(elapsedMs).inertialPoint, inertialPoint)
            assertEquals(original.getValue(elapsedMs).inertialSigmaM, inertialSigmaM)
        }
    }

    @Test
    fun missingRawSensorsAndWalkingAreExplicitlyUnsupported() {
        assertFailsWith<IllegalArgumentException> { InertialComparison().replay(recording().filterNot { it is TripEvent.Inertial }) }
        val walking = recording().map { if (it is TripEvent.Mode) it.copy(mode = TravelMode.FOOT) else it }
        assertFailsWith<IllegalArgumentException> { InertialComparison().replay(walking) }
    }

    @Test
    fun removingAccelerometerProducesMissingCoverageNotPerfectAccuracy() {
        val missing = recording().filterNot { it is TripEvent.Inertial && it.sample.kind == InertialKind.ACCELEROMETER }
        val result = InertialComparison().replay(missing, 10.0)
        assertTrue(result.samples.isEmpty())
        assertTrue(result.unpaired >= 30)
    }

    @Test
    fun saverRateAndGpsPhaseOffsetStillProducePairedSamples() {
        val saver = recording().filterNot { it is TripEvent.Inertial && it.sample.timestampNs / 1_000_000 % 100 != 20L }
        val result = InertialComparison().replay(saver, 10.0)
        val blind = result.samples.filter { it.blind }
        assertTrue(blind.size >= 25, result.summary())
        assertTrue(ReplayStats.of(blind.map { it.inertialErrorM }).p95M < 1.0, result.summary())
    }

    @Test
    fun rawExperimentInputsDoNotChangeEitherExistingEstimator() {
        val events = recording()
        val legacy = events.filterNot { it is TripEvent.Inertial }
        assertEquals(NativeComparison().replay(legacy, 10.0), NativeComparison().replay(events, 10.0))
        assertEquals(TripReplayer().replay(legacy, 10.0).samples, TripReplayer().replay(events, 10.0).samples)
    }
}
