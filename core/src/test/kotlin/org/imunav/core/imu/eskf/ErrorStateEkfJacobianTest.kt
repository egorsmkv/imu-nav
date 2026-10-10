package org.imunav.core.imu.eskf

import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Differentiate the quaternion composition and propagated nominal states, independently of F/reset formulas. */
class ErrorStateEkfJacobianTest {
    @Test
    fun resetMatchesQuaternionCompositionThroughoutAcceptedCorrectionRange() {
        val direction = Vector3(2.0 / 3, -1.0 / 3, 2.0 / 3)
        for (magnitude in listOf(0.0, 1e-8, 0.05, 0.3, 0.499)) {
            val angle = direction * magnitude
            val inverse = conjugate(Attitude.exp(angle))
            val actual = attitudeResetJacobian(angle)
            for (column in 0..2) {
                val perturbation = basis(column) * EPSILON
                val plus = rotationLog(inverse * Attitude.exp(angle + perturbation))
                val minus = rotationLog(inverse * Attitude.exp(angle - perturbation))
                val derivative = ((plus - minus) * (0.5 / EPSILON)).values()
                for (row in 0..2) assertEquals(derivative[row], actual[row, column], 1e-8, "angle=$magnitude row=$row col=$column")
            }
        }
    }

    @Test
    fun dynamicsMatchFiniteDifferencesOfAllFifteenNominalErrorCoordinates() {
        val initial = InertialState(
            0L,
            Vector3.ZERO,
            Vector3.ZERO,
            Attitude.exp(Vector3(0.2, -0.4, 0.6)),
            Vector3(0.1, -0.2, 0.3),
            Vector3(-0.02, 0.01, 0.03),
        )
        val force = Vector3(1.7, -2.1, 9.4)
        val rate = Vector3(0.3, -0.4, 0.6)
        val nominal = propagated(initial, force, rate)
        val dynamics = inertialErrorDynamics(initial.attitude.matrix(), force - initial.accelerometerBias, rate - initial.gyroscopeBias)
        for (column in 0 until 15) {
            val plus = stateError(propagated(perturbed(initial, column, EPSILON), force, rate), nominal)
            val minus = stateError(propagated(perturbed(initial, column, -EPSILON), force, rate), nominal)
            for (row in 0 until 15) {
                val transition = (plus[row] - minus[row]) / (2 * EPSILON)
                val derivative = (transition - if (row == column) 1.0 else 0.0) / STEP_S
                // O(dt) integration/linearization difference, checked at dt=10 microseconds.
                assertEquals(dynamics[row, column], derivative, 2e-4, "row=$row col=$column")
            }
        }
    }

    private fun propagated(initial: InertialState, force: Vector3, rate: Vector3): InertialState {
        val filter = ErrorStateEkf(initial, 5.0)
        assertTrue(filter.predict(10_000L, force, rate))
        return filter.state
    }

    private fun perturbed(initial: InertialState, column: Int, amount: Double): InertialState {
        val change = basis(column % 3) * amount
        return when (column / 3) {
            0 -> initial.copy(position = initial.position + change)
            1 -> initial.copy(velocity = initial.velocity + change)
            2 -> initial.copy(attitude = initial.attitude * Attitude.exp(change))
            3 -> initial.copy(accelerometerBias = initial.accelerometerBias + change)
            else -> initial.copy(gyroscopeBias = initial.gyroscopeBias + change)
        }
    }

    private fun stateError(actual: InertialState, nominal: InertialState): DoubleArray =
        (actual.position - nominal.position).values() + (actual.velocity - nominal.velocity).values() +
            rotationLog(conjugate(nominal.attitude) * actual.attitude).values() +
            (actual.accelerometerBias - nominal.accelerometerBias).values() + (actual.gyroscopeBias - nominal.gyroscopeBias).values()

    private fun conjugate(attitude: Attitude) = Attitude(attitude.w, -attitude.x, -attitude.y, -attitude.z)

    private fun rotationLog(attitude: Attitude): Vector3 {
        val imaginary = Vector3(attitude.x, attitude.y, attitude.z)
        val norm = imaginary.norm()
        return imaginary * if (norm < 1e-14) 2.0 else 2 * atan2(norm, attitude.w) / norm
    }

    private fun basis(index: Int) = Vector3(if (index == 0) 1.0 else 0.0, if (index == 1) 1.0 else 0.0, if (index == 2) 1.0 else 0.0)

    private companion object {
        const val EPSILON = 1e-6
        const val STEP_S = 1e-5
    }
}
