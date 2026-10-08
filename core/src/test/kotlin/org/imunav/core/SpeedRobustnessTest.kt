package org.imunav.core

import org.imunav.core.speed.NetSpeedEstimator
import org.imunav.core.speed.SpeedEstimate
import org.imunav.core.speed.SpeedFusion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Synthetic cases shared with the Rust speed tests keep live and replay evidence rules aligned. */
class SpeedRobustnessTest {
    @Test
    fun invalidSpeedsAreIgnoredWithoutContaminatingValidSources() {
        for (speed in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(10.0, SpeedFusion.fuse(speed, 0, 10.0, null), 1e-9)
            assertEquals(10.0, SpeedFusion.fuse(10.0, 0, speed, null), 1e-9)
            assertEquals(0.0, SpeedFusion.fuse(speed, 0, null, null))
        }
    }

    @Test
    fun malformedNetworkMetadataCannotContaminateFusion() {
        val valid = SpeedEstimate(12.0, 1.0, 5, 30.0)
        val malformed = listOf(
            valid.copy(sigmaMps = -1.0), valid.copy(sigmaMps = Double.NaN), valid.copy(sigmaMps = Double.POSITIVE_INFINITY),
            valid.copy(spanS = -1.0), valid.copy(spanS = Double.NaN), valid.copy(spanS = Double.POSITIVE_INFINITY),
            valid.copy(speedMps = -1.0), valid.copy(speedMps = Double.NaN), valid.copy(speedMps = Double.POSITIVE_INFINITY),
        )
        for (network in malformed) {
            assertEquals(0.0, SpeedFusion.fuse(null, 0, null, network))
            assertEquals(10.0, SpeedFusion.fuse(10.0, 0, null, network), 1e-9)
        }
        assertEquals(12.0, SpeedFusion.fuse(null, 0, null, valid.copy(sigmaMps = 0.0)), 1e-9)
    }

    @Test
    fun overflowedFusionDoesNotBecomeAnApparentlyValidCappedSpeed() {
        assertEquals(0.0, SpeedFusion.fuse(null, 0, null, SpeedEstimate(Double.MAX_VALUE, 0.3, 5, 30.0)))
    }

    @Test
    fun historicalQueriesExcludeFutureSamplesWithoutConsumingThem() {
        val estimator = NetSpeedEstimator()
        for (index in 0..3) estimator.add(index * 100.0, 20.0, 1_000 + index * 10_000L)
        assertNull(estimator.estimate(0))
        assertNull(estimator.strictEstimate(0))
        val current = assertNotNull(estimator.strictEstimate(31_000))
        estimator.add(1_000.0, 20.0, 41_000)
        assertEquals(current, estimator.strictEstimate(31_000))
    }

    @Test
    fun tooFewInliersCannotRestoreTheContaminatedOriginalFit() {
        val estimator = NetSpeedEstimator()
        listOf(0.0, 100.0, 200.0, 10_000.0).forEachIndexed { index, position -> estimator.add(position, 20.0, index * 10_000L) }
        assertNull(estimator.estimate(30_000))
        assertNull(estimator.strictEstimate(30_000))
    }

    @Test
    fun degenerateFiniteObservationsCannotPublishNonfiniteRegression() {
        val estimator = NetSpeedEstimator()
        for (index in 0..3) estimator.add(index * 100.0, Double.MAX_VALUE, index * 10_000L)
        assertNull(estimator.estimate(30_000))
        assertNull(estimator.strictEstimate(30_000))
    }

    @Test
    fun malformedSamplesDoNotConsumeTheirTimestampOrPoisonRegression() {
        for ((position, accuracy) in listOf(Double.NaN to 20.0, Double.POSITIVE_INFINITY to 20.0, 0.0 to -1.0, 0.0 to Double.NaN, 0.0 to Double.POSITIVE_INFINITY)) {
            val estimator = NetSpeedEstimator()
            estimator.add(position, accuracy, 0)
            for (index in 0..3) estimator.add(index * 100.0, 20.0, index * 10_000L)
            val estimate = assertNotNull(estimator.strictEstimate(30_000))
            assertEquals(10.0, estimate.speedMps, 1e-9)
            assertEquals(4, estimate.samples)
        }
    }

    @Test
    fun expiryAndTimestampExtremesHaveTheSameWindowRules() {
        for (firstMs in listOf(Long.MIN_VALUE, -30_000L, 1_000L, Long.MAX_VALUE - 30_000)) {
            val estimator = NetSpeedEstimator()
            for (index in 0..3) estimator.add(index * 100.0, 20.0, firstMs + index * 10_000L)
            assertEquals(10.0, assertNotNull(estimator.strictEstimate(firstMs + 30_000)).speedMps, 1e-9)
            if (firstMs != Long.MIN_VALUE) assertNull(estimator.strictEstimate(firstMs - 1))
        }
        val estimator = NetSpeedEstimator()
        for (index in 0..3) estimator.add(index * 100.0, 20.0, index * 30_000L)
        assertNotNull(estimator.strictEstimate(90_000))
        assertNull(estimator.strictEstimate(90_001))
        assertNull(estimator.strictEstimate(89_999))
    }
}
