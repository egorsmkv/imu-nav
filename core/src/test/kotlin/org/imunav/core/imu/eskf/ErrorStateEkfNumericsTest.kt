package org.imunav.core.imu.eskf

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Closed-form and metamorphic checks independent of the filter's matrix implementation. */
class ErrorStateEkfNumericsTest {
    private val initial = InertialState(0, Vector3.ZERO, Vector3.ZERO, Attitude.IDENTITY)

    @Test
    fun rotatingAttitudeCovarianceMatchesContinuousTimeTruthAcrossInputRates() {
        for (angularRate in listOf(5.0, 20.0, 35.0)) {
            val squaredRate = angularRate * angularRate
            // Exact decoupled transverse attitude/bias covariance, with the independently specified defaults.
            val expected = 0.15 * 0.15 + 0.03 * 0.03 * 2 * (1 - cos(angularRate)) / squaredRate +
                0.01 * 0.01 + 0.0005 * 0.0005 * (2 / squaredRate - 2 * sin(angularRate) / (squaredRate * angularRate))
            for (stepNs in listOf(20_000_000L, 10_000_000L, 2_000_000L)) {
                val filter = ErrorStateEkf(initial, 5.0)
                for (timestampNs in stepNs..1_000_000_000L step stepNs) {
                    assertTrue(filter.predict(timestampNs, Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2), Vector3(0.0, 0.0, angularRate)))
                }
                assertEquals(expected, filter.covariance()[6][6], expected * 0.001, "angular_rate=$angularRate step_ns=$stepNs")
                assertEquals(expected, filter.covariance()[7][7], expected * 0.001)
            }
        }
    }

    @Test
    fun horizontalSigmaRetainsAnisotropyAndCorrelationAcrossScales() {
        for (scale in doubleArrayOf(1e-300, 1.0, 1e300)) {
            // Outer product of (3, 4) has eigenvalues 0 and 25; diagonal case has 9 and 16.
            val unit = sqrt(scale)
            assertEquals(5.0, horizontalPositionSigma(9 * scale, 16 * scale, 12 * scale) / unit, 1e-14)
            assertEquals(5.0, horizontalPositionSigma(16 * scale, 9 * scale, -12 * scale) / unit, 1e-14)
            assertEquals(4.0, horizontalPositionSigma(9 * scale, 16 * scale, 0.0) / unit, 1e-14)
        }
    }

    @Test
    fun horizontalSigmaHandlesZeroSubnormalAndUnrepresentableEigenvalue() {
        assertEquals(0.0, horizontalPositionSigma(0.0, 0.0, 0.0))
        assertEquals(sqrt(Double.MIN_VALUE), horizontalPositionSigma(Double.MIN_VALUE, Double.MIN_VALUE, 0.0))
        val maximum = Double.MAX_VALUE
        // The covariance is PSD and its sigma fits in Double even though its eigenvalue does not.
        assertEquals(sqrt(2.0), horizontalPositionSigma(maximum, maximum, maximum) / sqrt(maximum), 1e-15)
    }

    @Test
    fun largeFiniteInitialCovarianceSurvivesPrediction() {
        val filter = ErrorStateEkf(initial, 1e154)
        assertEquals(1.0, filter.horizontalSigmaM / 1e154, 1e-15)
        assertTrue(filter.predict(20_000_000, Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2), Vector3.ZERO))
        assertEquals(1.0, filter.horizontalSigmaM / 1e154, 1e-15)
        assertTrue(filter.covariance().all { row -> row.all { it.isFinite() } })
    }

    @Test
    fun symmetrizationAvoidsOverflowAndPreservesSubnormalVariances() {
        for (sign in doubleArrayOf(-1.0, 1.0)) {
            val matrix = Matrix(2, 2).apply {
                this[0, 0] = 1e308
                this[1, 1] = Double.MIN_VALUE
                this[0, 1] = sign * 1e308
                this[1, 0] = sign * 8e307
            }.symmetric()
            assertEquals(1e308, matrix[0, 0])
            assertEquals(Double.MIN_VALUE, matrix[1, 1])
            assertEquals(sign * 0.9, matrix[0, 1] / 1e308, 1e-15)
            assertEquals(matrix[0, 1], matrix[1, 0])
        }
        assertFalse(Matrix(1, 1) { _, _ -> Double.POSITIVE_INFINITY }.symmetric().finite())
        assertFalse(Matrix(1, 1) { _, _ -> Double.NaN }.symmetric().finite())
    }

    @Test
    fun initialSigmaRejectsZeroOrNonfiniteVariance() {
        for (invalid in doubleArrayOf(0.0, -1.0, 1e-200, 1e200, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { ErrorStateEkf(initial, invalid) }
        }
        val small = ErrorStateEkf(initial, 1e-150)
        assertEquals(1.0, small.horizontalSigmaM / 1e-150, 1e-15)
    }

    @Test
    fun everyNoiseDensityRejectsZeroOrNonfiniteSquaredDensity() {
        val defaults = InertialTuning()
        for (invalid in doubleArrayOf(0.0, -1.0, 1e-200, 1e200, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { defaults.copy(accelerometerNoise = invalid) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(gyroscopeNoise = invalid) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(accelerometerBiasWalk = invalid) }
            assertFailsWith<IllegalArgumentException> { defaults.copy(gyroscopeBiasWalk = invalid) }
        }
    }

    @Test
    fun scalarCorrectionMatchesGaussianPosterior() {
        val filter = ErrorStateEkf(initial, 5.0)
        assertTrue(filter.correct(intArrayOf(0), doubleArrayOf(2.0), doubleArrayOf(4.0)))
        // Prior variance 25 and measurement variance 4: gain 25/29, posterior variance 100/29.
        assertEquals(50.0 / 29.0, filter.state.position.x, 1e-14)
        assertEquals(100.0 / 29.0, filter.covariance()[0][0], 1e-14)
        assertEquals(25.0, filter.covariance()[1][1])
        assertEquals(Vector3.ZERO, filter.state.velocity)
    }

    @Test
    fun jointCorrectionIsInvariantToMeasurementOrdering() {
        val ordered = ErrorStateEkf(initial, 5.0)
        val permuted = ErrorStateEkf(initial, 5.0)
        for (filter in listOf(ordered, permuted)) {
            assertTrue(filter.predict(20_000_000, Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2), Vector3.ZERO))
        }
        assertTrue(ordered.correct(intArrayOf(0, 3), doubleArrayOf(0.2, 0.1), doubleArrayOf(4.0, 0.25)))
        assertTrue(permuted.correct(intArrayOf(3, 0), doubleArrayOf(0.1, 0.2), doubleArrayOf(0.25, 4.0)))
        assertTrue((ordered.state.position - permuted.state.position).norm() < 1e-14)
        assertTrue((ordered.state.velocity - permuted.state.velocity).norm() < 1e-14)
        val expected = ordered.covariance()
        val actual = permuted.covariance()
        for (row in expected.indices) {
            for (column in expected.indices) assertEquals(expected[row][column], actual[row][column], 1e-13)
        }
    }
}
