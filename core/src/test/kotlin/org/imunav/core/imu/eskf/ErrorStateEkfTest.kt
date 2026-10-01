package org.imunav.core.imu.eskf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Analytic trajectories exercise frame conventions, error cross-covariance and numerical stability. */
class ErrorStateEkfTest {
    private val gravity = Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2)

    private fun filter(attitude: Attitude = Attitude.IDENTITY, velocity: Vector3 = Vector3.ZERO) = ErrorStateEkf(InertialState(0, Vector3.ZERO, velocity, attitude), 5.0)

    @Test
    fun gravityCancelsInAnArbitrarilyTiltedPhone() {
        val rotation = Attitude.exp(Vector3(0.7, -0.2, 1.1)).normalized()
        val bodyGravity = Attitude(rotation.w, -rotation.x, -rotation.y, -rotation.z).rotate(gravity)
        val filter = filter(rotation)
        for (step in 1..1000) assertTrue(filter.predict(step * 20_000_000L, bodyGravity, Vector3.ZERO))
        assertTrue(filter.state.position.norm() < 1e-9)
        assertTrue(filter.state.velocity.norm() < 1e-9)
        positiveDefinite(filter)
    }

    @Test
    fun constantVelocityIsNotMistakenForStationaryFromQuietImu() {
        val filter = filter(velocity = Vector3(4.0, 12.0, 0.0))
        for (step in 1..500) assertTrue(filter.predict(step * 20_000_000L, gravity, Vector3.ZERO))
        assertEquals(40.0, filter.state.position.x, 1e-9)
        assertEquals(120.0, filter.state.position.y, 1e-9)
        assertEquals(Vector3(4.0, 12.0, 0.0), filter.state.velocity)
    }

    @Test
    fun constantBodyAccelerationUsesAttitudeAndQuadraticPosition() {
        val filter = filter(Attitude.exp(Vector3(0.0, 0.0, PI / 2)))
        for (step in 1..500) filter.predict(step * 20_000_000L, gravity + Vector3(2.0, 0.0, 0.0), Vector3.ZERO)
        assertEquals(0.0, filter.state.position.x, 1e-8)
        assertEquals(100.0, filter.state.position.y, 1e-8)
        assertEquals(20.0, filter.state.velocity.y, 1e-8)
    }

    @Test
    fun rotatingSpecificForceFollowsCircularMotion() {
        val rate = 0.2
        val speed = 10.0
        val filter = filter(velocity = Vector3(speed, 0.0, 0.0))
        for (step in 1..1000) {
            assertTrue(filter.predict(step * 10_000_000L, gravity + Vector3(0.0, speed * rate, 0.0), Vector3(0.0, 0.0, rate)))
        }
        assertEquals(speed / rate * kotlin.math.sin(2.0), filter.state.position.x, 0.002)
        assertEquals(speed / rate * (1 - kotlin.math.cos(2.0)), filter.state.position.y, 0.002)
        assertEquals(kotlin.math.cos(1.0), filter.state.attitude.w, 1e-9)
        positiveDefinite(filter)
    }

    @Test
    fun propagationIncludesAttitudeAndBiasCrossCovariances() {
        val filter = filter()
        filter.predict(20_000_000, gravity, Vector3.ZERO)
        val covariance = filter.covariance()
        assertTrue(covariance[3][7] > 0, "pitch error rotates gravity into east velocity")
        assertTrue(covariance[4][6] < 0, "roll error rotates gravity into negative north velocity")
        assertTrue(covariance[3][9] < 0, "positive accelerometer bias reduces east acceleration")
        assertTrue(covariance[6][12] < 0, "positive gyro bias reduces roll rate")
        assertTrue(covariance[0][3] > 0)
        positiveDefinite(filter)
    }

    @Test
    fun gpsLearnsObservableVerticalAccelerometerBias() {
        val filter = filter()
        for (step in 1..3000) {
            assertTrue(filter.predict(step * 20_000_000L, gravity + Vector3(0.0, 0.0, 0.15), Vector3.ZERO))
            if (step % 50 == 0) {
                assertTrue(filter.correct(intArrayOf(0, 1, 2, 3, 4, 5), DoubleArray(6), doubleArrayOf(4.0, 4.0, 4.0, 0.25, 0.25, 0.25)))
                positiveDefinite(filter)
            }
        }
        assertEquals(0.15, filter.state.accelerometerBias.z, 0.025)
        assertTrue(abs(filter.state.position.z) < 0.2)
        assertTrue(filter.covariance()[8][8] >= 0.25, "stationary GPS cannot identify yaw")
    }

    @Test
    fun gpsCorrectsTiltAndObservableGyroBiasWithoutBreakingCovariance() {
        val filter = filter(Attitude.exp(Vector3(0.06, -0.04, 0.0)))
        for (step in 1..3000) {
            assertTrue(filter.predict(step * 20_000_000L, gravity, Vector3(0.004, 0.0, 0.0)))
            if (step % 50 == 0) {
                assertTrue(filter.correct(intArrayOf(0, 1, 2, 3, 4, 5), DoubleArray(6), doubleArrayOf(4.0, 4.0, 4.0, 0.25, 0.25, 0.25)))
                positiveDefinite(filter)
            }
        }
        assertEquals(0.004, filter.state.gyroscopeBias.x, 0.001)
        assertTrue(abs(filter.state.attitude.x) < 0.01)
        assertTrue(abs(filter.state.attitude.y) < 0.01)
        assertTrue(filter.state.position.norm() < 0.5)
    }

    @Test
    fun josephUpdateReducesUncertaintyAndPublishesDefensiveCovariance() {
        val filter = filter()
        val before = filter.horizontalSigmaM
        assertTrue(filter.correct(intArrayOf(0, 1), doubleArrayOf(2.0, -1.0), doubleArrayOf(1.0, 1.0)))
        assertTrue(filter.horizontalSigmaM < before)
        assertTrue(filter.state.position.x in 1.0..2.0)
        val copy = filter.covariance()
        copy[0][0] = -100.0
        assertTrue(filter.covariance()[0][0] > 0)
        positiveDefinite(filter)
    }

    @Test
    fun outliersAndMalformedMeasurementsDoNotChangeAnyState() {
        val filter = filter()
        val before = filter.state
        val covariance = filter.covariance().map { it.toList() }
        assertFalse(filter.correct(intArrayOf(0, 1), doubleArrayOf(1000.0, -1000.0), doubleArrayOf(1.0, 1.0)))
        assertFalse(filter.correct(intArrayOf(0), doubleArrayOf(Double.NaN), doubleArrayOf(1.0)))
        assertFalse(filter.correct(intArrayOf(0), doubleArrayOf(0.0), doubleArrayOf(-1.0)))
        assertFalse(filter.correct(intArrayOf(0, 0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)))
        assertFalse(filter.correct(intArrayOf(8), doubleArrayOf(0.0), doubleArrayOf(1.0)))
        assertEquals(before, filter.state)
        assertEquals(covariance, filter.covariance().map { it.toList() })
    }

    @Test
    fun duplicateBackwardsMissingAndSaturatedImuCannotAdvance() {
        val filter = filter()
        assertTrue(filter.predict(20_000_000, gravity, Vector3.ZERO))
        val before = filter.state
        assertFalse(filter.predict(20_000_000, gravity, Vector3.ZERO))
        assertFalse(filter.predict(10_000_000, gravity, Vector3.ZERO))
        assertFalse(filter.predict(1_020_000_000, gravity, Vector3.ZERO))
        assertFalse(filter.predict(40_000_000, Vector3(Double.NaN, 0.0, 0.0), Vector3.ZERO))
        assertFalse(filter.predict(40_000_000, Vector3(1000.0, 0.0, 0.0), Vector3.ZERO))
        assertFalse(filter.predict(40_000_000, gravity, Vector3(100.0, 0.0, 0.0)))
        assertEquals(before, filter.state)
    }

    @Test
    fun continuousNoiseAndCovarianceAreStableAcrossSampleRates() {
        val slower = filter()
        val faster = filter()
        for (step in 1..500) slower.predict(step * 20_000_000L, gravity, Vector3.ZERO)
        for (step in 1..1000) faster.predict(step * 10_000_000L, gravity, Vector3.ZERO)
        val slow = slower.covariance()
        val fast = faster.covariance()
        for (row in slow.indices) for (column in slow.indices) assertEquals(slow[row][column], fast[row][column], 0.01)
        assertTrue(slower.horizontalSigmaM > 100.0, "unaided inertial uncertainty must grow")
    }

    private fun positiveDefinite(filter: ErrorStateEkf) {
        val covariance = filter.covariance()
        for (row in covariance.indices) for (column in covariance.indices) assertEquals(covariance[row][column], covariance[column][row], 1e-10)
        assertNotNull(Matrix(15, 15) { row, column -> covariance[row][column] }.cholesky())
        val quaternion = filter.state.attitude
        assertEquals(1.0, quaternion.w * quaternion.w + quaternion.x * quaternion.x + quaternion.y * quaternion.y + quaternion.z * quaternion.z, 1e-12)
    }
}
