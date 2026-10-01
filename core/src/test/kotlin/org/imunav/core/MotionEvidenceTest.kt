package org.imunav.core

import org.imunav.core.geo.GeoPoint
import org.imunav.core.imu.ImuSample
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NetSample
import org.imunav.core.nav.NetworkTracker
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cached positions and repeated ticks must not manufacture fresh evidence for native motion. */
class MotionEvidenceTest {
    private val network = NetworkTracker()
    private val engine = NavigationEngine(listener = object : NavListener {}, networkTracker = network)

    private fun start(mode: TravelMode = TravelMode.CAR) {
        val points = listOf(GeoPoint(50.45, 30.52), GeoPoint(50.48, 30.52))
        val route = Route(points, listOf(Step("depart", null, "test", 3000.0, 300.0, 0), Step("arrive", null, "", 0.0, 0.0, 1)), 300.0)
        engine.start(route, points.last(), nowMs = 1000, mode = mode)
        for (time in 1020L..31_000L step 20) quietSample(time)
    }

    private fun quietSample(time: Long) {
        engine.onImu(ImuSample(time, null, 0f, floatArrayOf(0.03f, 0f, 0f), floatArrayOf(0.005f, 0f, 0f)))
    }

    @Test
    fun repeatedTicksCannotRefreshImuExpiry() {
        start()
        val evidence = assertNotNull(engine.motionEvidence(31_000))
        assertEquals(0.0, evidence.factor)
        assertEquals(33_000L, evidence.validUntilMs)
        assertEquals(evidence.validUntilMs, assertNotNull(engine.motionEvidence(32_999)).validUntilMs)
        assertNull(engine.motionEvidence(33_000))
        assertNull(engine.motionEvidence(30_999))
    }

    @Test
    fun duplicateCellCoordinatesCannotRefreshMovementVeto() {
        start()
        for (index in 0..4) {
            network.record(NetSample(11_000L + index * 5000, 100.0 + index * 50, 20.0, 0.0), 50.45 + index * 0.001, 30.52)
        }
        assertTrue(assertNotNull(engine.motionEvidence(31_000)).networkMoving)
        for (time in 31_020L..42_000L step 20) quietSample(time)
        network.record(NetSample(42_000, 300.0, 20.0, 0.0), 50.45 + 4 * 0.001, 30.52)
        assertEquals(31_000L, network.history.last().elapsedMs)
        assertFalse(assertNotNull(engine.motionEvidence(42_000)).networkMoving)
    }

    @Test
    fun walkingDoesNotExportCarMotionHints() {
        start(TravelMode.FOOT)
        val hint = assertNotNull(engine.motionEvidence(31_000))
        assertTrue(hint.walking)
        assertEquals(0.0, hint.factor)
        assertFalse(hint.networkMoving)
        assertNull(engine.motionEvidence(33_000))
    }

    @Test
    fun walkingStepExpiryIsNotRefreshedByPollingOrDuplicateSteps() {
        start(TravelMode.FOOT)
        engine.onStep(31_000)
        engine.onStep(31_500)
        engine.onStep(31_500)
        engine.onStep(30_000)
        val hint = assertNotNull(engine.motionEvidence(31_500))
        assertEquals(1.44, hint.cruiseSpeedMps)
        assertEquals(34_000L, hint.validUntilMs)
        assertEquals(hint.validUntilMs, assertNotNull(engine.motionEvidence(33_000)).validUntilMs)
        assertEquals(0.0, assertNotNull(engine.motionEvidence(34_001)).cruiseSpeedMps)
    }

    @Test
    fun walkingWithoutStepsOrFreshImuHasNoMovementEvidence() {
        start(TravelMode.FOOT)
        assertNull(engine.motionEvidence(100_000))
    }
}
