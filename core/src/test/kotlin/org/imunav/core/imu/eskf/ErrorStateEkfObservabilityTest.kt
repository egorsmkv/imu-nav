package org.imunav.core.imu.eskf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Observable GPS motion must not be confused with uniquely identifiable phone orientation/biases. */
class ErrorStateEkfObservabilityTest {
    @Test
    fun stationaryTiltAndAccelerometerBiasCanExplainIdenticalMeasurements() {
        val reference = filter()
        val attitude = Attitude.exp(Vector3(0.08, -0.05, 0.4)).normalized()
        val inverse = Attitude(attitude.w, -attitude.x, -attitude.y, -attitude.z)
        // R * (measuredForce - bias) = gravity for either hypothesis, exactly (not just first order).
        val bias = GRAVITY - inverse.rotate(GRAVITY)
        val alternative = filter(attitude = attitude, accelerometerBias = bias)
        for (step in 1..100) {
            for (candidate in listOf(reference, alternative)) {
                assertTrue(candidate.predict(step * STEP_NS, GRAVITY, Vector3.ZERO))
                if (step % 10 == 0) assertTrue(candidate.correct(COORDINATES, DoubleArray(6), VARIANCES))
            }
        }
        assertTrue((reference.state.position - alternative.state.position).norm() < 1e-10)
        assertTrue((reference.state.velocity - alternative.state.velocity).norm() < 1e-10)
        assertTrue((reference.state.accelerometerBias - alternative.state.accelerometerBias).norm() > 0.5)
        assertEquals(attitude.w, alternative.state.attitude.w, 1e-10)
        assertEquals(attitude.x, alternative.state.attitude.x, 1e-10)
    }

    @Test
    fun gpsCourseDoesNotSupplyPhoneYawOrVerticalGyroBias() {
        val velocity = Vector3(4.0, 12.0, 0.0)
        val reference = filter(velocity = velocity)
        val alternative = filter(velocity = velocity, attitude = Attitude.exp(Vector3(0.0, 0.0, 0.7)), gyroscopeBias = Vector3(0.0, 0.0, 0.01))
        for (step in 1..100) {
            val time = step * STEP_NS / 1e9
            val observation = doubleArrayOf(velocity.x * time, velocity.y * time, 0.0, velocity.x, velocity.y, 0.0)
            for (candidate in listOf(reference, alternative)) {
                assertTrue(candidate.predict(step * STEP_NS, GRAVITY, Vector3.ZERO))
                if (step % 10 == 0) assertTrue(candidate.correct(COORDINATES, observation, VARIANCES))
            }
        }
        assertTrue((reference.state.position - alternative.state.position).norm() < 1e-10)
        assertTrue((reference.state.velocity - alternative.state.velocity).norm() < 1e-10)
        val expected = Attitude.exp(Vector3(0.0, 0.0, 0.7 - 0.01 * 2.0))
        assertEquals(expected.z, alternative.state.attitude.z, 1e-10)
        assertEquals(0.01, alternative.state.gyroscopeBias.z, 1e-10)
    }

    @Test
    fun turnMakesAnInitialYawDifferenceVisibleInGpsVelocity() {
        val velocity = Vector3(5.0, 0.0, 0.0)
        val reference = filter(velocity = velocity)
        val alternative = filter(velocity = velocity, attitude = Attitude.exp(Vector3(0.0, 0.0, 0.1)))
        val centripetalForce = GRAVITY + Vector3(0.0, 1.0, 0.0)
        val angularRate = Vector3(0.0, 0.0, 0.2)
        for (step in 1..50) {
            assertTrue(reference.predict(step * STEP_NS, centripetalForce, angularRate))
            assertTrue(alternative.predict(step * STEP_NS, centripetalForce, angularRate))
        }
        assertTrue((reference.state.velocity - alternative.state.velocity).norm() > 0.05)
        // This distinguishes these two hypotheses, not every attitude/bias combination.
    }

    @Test
    fun stationaryGpsLeavesYawAndVerticalGyroCovarianceUnaided() {
        val aided = filter()
        val unaided = filter()
        for (step in 1..100) {
            assertTrue(aided.predict(step * STEP_NS, GRAVITY, Vector3.ZERO))
            assertTrue(unaided.predict(step * STEP_NS, GRAVITY, Vector3.ZERO))
            if (step % 10 == 0) assertTrue(aided.correct(COORDINATES, DoubleArray(6), VARIANCES))
        }
        val expected = unaided.covariance()
        val actual = aided.covariance()
        for (row in intArrayOf(8, 14)) {
            for (column in intArrayOf(8, 14)) assertEquals(expected[row][column], actual[row][column], 1e-12)
        }
        assertTrue(actual[8][8] > 0.25, "unobserved yaw uncertainty grows even with perfect GPS motion")
        assertTrue(actual[2][2] < expected[2][2], "GPS still informs observable position")
    }

    @Test
    fun gpsOutageGrowsPositionUncertaintyAndReturnDoesNotCollapseYaw() {
        val filter = filter()
        for (step in 1..100) {
            assertTrue(filter.predict(step * STEP_NS, GRAVITY, Vector3.ZERO))
            if (step % 10 == 0) assertTrue(filter.correct(COORDINATES, DoubleArray(6), VARIANCES))
        }
        val aidedSigma = filter.horizontalSigmaM
        // IMU stays continuous; only GPS is absent, so this is not the >250 ms input-gap case.
        for (step in 101..400) assertTrue(filter.predict(step * STEP_NS, GRAVITY, Vector3.ZERO))
        val outageSigma = filter.horizontalSigmaM
        val yawVariance = filter.covariance()[8][8]
        assertTrue(outageSigma > aidedSigma)
        assertTrue(filter.correct(COORDINATES, DoubleArray(6), VARIANCES))
        assertTrue(filter.horizontalSigmaM < outageSigma)
        assertEquals(yawVariance, filter.covariance()[8][8], 1e-12)
        assertTrue(filter.state.position.norm() < 1e-10)
    }

    private fun filter(
        velocity: Vector3 = Vector3.ZERO,
        attitude: Attitude = Attitude.IDENTITY,
        accelerometerBias: Vector3 = Vector3.ZERO,
        gyroscopeBias: Vector3 = Vector3.ZERO,
    ) = ErrorStateEkf(InertialState(0, Vector3.ZERO, velocity, attitude, accelerometerBias, gyroscopeBias), 5.0)

    private companion object {
        const val STEP_NS = 20_000_000L
        val GRAVITY = Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2)
        val COORDINATES = intArrayOf(0, 1, 2, 3, 4, 5)
        val VARIANCES = doubleArrayOf(4.0, 4.0, 4.0, 0.25, 0.25, 0.25)
    }
}
