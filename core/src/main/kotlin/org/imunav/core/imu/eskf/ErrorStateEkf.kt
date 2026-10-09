package org.imunav.core.imu.eskf

import kotlin.math.ceil

/** Initial experimental noise densities (SI units / sqrt(Hz)); device calibration is still required. */
data class InertialTuning(
    val accelerometerNoise: Double = 0.15,
    val gyroscopeNoise: Double = 0.01,
    val accelerometerBiasWalk: Double = 0.005,
    val gyroscopeBiasWalk: Double = 0.0005,
) {
    init {
        require(listOf(accelerometerNoise, gyroscopeNoise, accelerometerBiasWalk, gyroscopeBiasWalk).all { hasFinitePositiveSquare(it) }) {
            "noise densities must have finite positive squares"
        }
    }
}

/** Nominal state. Biases are in device axes; position/velocity in local east/north/up metres and m/s. */
data class InertialState(
    val timestampNs: Long,
    val position: Vector3,
    val velocity: Vector3,
    val attitude: Attitude,
    val accelerometerBias: Vector3 = Vector3.ZERO,
    val gyroscopeBias: Vector3 = Vector3.ZERO,
)

/**
 * Experimental 15-error-state inertial EKF, independent of the route estimator. Error ordering is
 * [position, velocity, local attitude, accelerometer bias, gyroscope bias], three coordinates each.
 * Uses right-multiplicative attitude errors (q_true = q_nominal * Exp(error)), fixed ENU gravity,
 * midpoint nominal integration, third-order transition, Joseph updates and attitude reset Jacobian.
 * See Solà, arXiv:1711.02508 sections 5–6. Earth rotation, lever arms and scale errors are not modeled.
 * This covariance is a model diagnostic, NOT a navigation safety radius. No Android/JNI dependencies.
 */
class ErrorStateEkf(initial: InertialState, positionSigmaM: Double, private val tuning: InertialTuning = InertialTuning()) {
    var state = initial.copy(attitude = initial.attitude.normalized())
        private set
    private var covariance = Matrix(STATE_SIZE, STATE_SIZE)

    init {
        require(initial.timestampNs >= 0 && initial.position.isFinite() && initial.velocity.isFinite())
        require(initial.accelerometerBias.isFinite() && initial.gyroscopeBias.isFinite())
        require(hasFinitePositiveSquare(positionSigmaM)) { "position sigma must have a finite positive square" }
        val sigmas = doubleArrayOf(positionSigmaM, positionSigmaM, 30.0, 5.0, 5.0, 5.0, 0.15, 0.15, 0.5, 0.3, 0.3, 0.3, 0.03, 0.03, 0.03)
        for (index in sigmas.indices) covariance[index, index] = sigmas[index] * sigmas[index]
    }

    /** Largest horizontal covariance eigenvalue's square root, including east/north correlation. */
    val horizontalSigmaM: Double
        get() = horizontalPositionSigma(covariance[0, 0], covariance[1, 1], covariance[0, 1])

    /** Defensive copy for numerical diagnostics/tests; modifying it cannot change the filter. */
    fun covariance(): Array<DoubleArray> = Array(STATE_SIZE) { row -> DoubleArray(STATE_SIZE) { column -> covariance[row, column] } }

    /**
     * Advance using body specific force INCLUDING gravity (m/s²), and body angular rate (rad/s).
     * Both are held over the interval. Invalid/nonmonotonic data and gaps >250 ms are rejected without
     * mutation; the caller must discard the session after a gap, not integrate across missing motion.
     */
    fun predict(timestampNs: Long, specificForce: Vector3, angularRate: Vector3): Boolean {
        if (timestampNs <= state.timestampNs) return false
        val dt = (timestampNs - state.timestampNs) / 1e9
        if (!specificForce.isFinite() || !angularRate.isFinite() || dt <= 0.0 || dt > MAX_GAP_S) return false
        if (specificForce.norm() > MAX_FORCE || angularRate.norm() > MAX_RATE) return false
        val steps = ceil(dt / MAX_STEP_S).toInt()
        val previousState = state
        val previousCovariance = covariance
        repeat(steps) { advance(dt / steps, specificForce, angularRate) }
        state = state.copy(timestampNs = timestampNs)
        if (!healthy()) {
            state = previousState
            covariance = previousCovariance
            return false
        }
        return true
    }

    private fun advance(dt: Double, specificForce: Vector3, angularRate: Vector3) {
        val force = specificForce - state.accelerometerBias
        val rate = angularRate - state.gyroscopeBias
        val midpoint = (state.attitude * Attitude.exp(rate * (dt / 2))).normalized()
        val acceleration = midpoint.rotate(force) + Vector3(0.0, 0.0, -GRAVITY_MPS2)
        covariance = propagateCovariance(dt, midpoint.matrix(), force, rate)
        state = state.copy(
            position = state.position + state.velocity * dt + acceleration * (dt * dt / 2),
            velocity = state.velocity + acceleration * dt,
            attitude = (state.attitude * Attitude.exp(rate * dt)).normalized(),
        )
    }

    private fun propagateCovariance(dt: Double, rotation: Matrix, force: Vector3, rate: Vector3): Matrix {
        val dynamics = Matrix(STATE_SIZE, STATE_SIZE).apply {
            block(0, 3, Matrix.identity(3))
            block(3, 6, (rotation * Matrix.skew(force)) * -1.0)
            block(3, 9, rotation * -1.0)
            block(6, 6, Matrix.skew(rate) * -1.0)
            block(6, 12, Matrix.identity(3) * -1.0)
        }
        val squared = dynamics * dynamics
        val cubed = squared * dynamics
        val transition = Matrix.identity(STATE_SIZE) + dynamics * dt + squared * (dt * dt / 2) + cubed * (dt * dt * dt / 6)
        // Integrate continuous white noise through the transition with Simpson quadrature. Each
        // term is PSD, and includes velocity-position and bias-attitude cross-covariance growth.
        val densities = doubleArrayOf(0.0, tuning.accelerometerNoise, tuning.gyroscopeNoise, tuning.accelerometerBiasWalk, tuning.gyroscopeBiasWalk)
        val noise = Matrix(STATE_SIZE, STATE_SIZE) { row, column -> if (row == column) densities[row / 3] * densities[row / 3] else 0.0 }
        val half = Matrix.identity(STATE_SIZE) + dynamics * (dt / 2) + squared * (dt * dt / 8) + cubed * (dt * dt * dt / 48)
        val integrated = (noise + (half * noise * half.transpose()) * 4.0 + transition * noise * transition.transpose()) * (dt / 6)
        return (transition * covariance * transition.transpose() + integrated).symmetric()
    }

    /**
     * Correct selected position/velocity coordinates at EXACTLY the state's time. Coordinates are
     * indices 0..5, with independent measurement variances. Caller must enforce GPS trust and time
     * alignment. All coordinates are gated jointly; an outlier leaves every state/covariance intact.
     */
    fun correct(coordinates: IntArray, observations: DoubleArray, variances: DoubleArray): Boolean {
        if (coordinates.isEmpty() || coordinates.size > 6 || coordinates.distinct().size != coordinates.size || coordinates.any { it !in 0..5 }) return false
        if (observations.size != coordinates.size || variances.size != coordinates.size) return false
        if (observations.any { !it.isFinite() } || variances.any { !it.isFinite() || it <= 0 }) return false
        val nominal = state.position.values() + state.velocity.values()
        val residual = DoubleArray(coordinates.size) { observations[it] - nominal[coordinates[it]] }
        val innovation = Matrix(coordinates.size, coordinates.size) { row, column ->
            covariance[coordinates[row], coordinates[column]] + if (row == column) variances[row] else 0.0
        }
        val factor = innovation.cholesky() ?: return false
        val weighted = factor.solveCholesky(residual)
        val distance = residual.indices.sumOf { residual[it] * weighted[it] }
        if (!distance.isFinite() || distance > INNOVATION_LIMITS[coordinates.size - 1]) return false
        val gain = Matrix(STATE_SIZE, coordinates.size)
        for (row in 0 until STATE_SIZE) {
            val solution = factor.solveCholesky(DoubleArray(coordinates.size) { covariance[row, coordinates[it]] })
            for (column in coordinates.indices) gain[row, column] = solution[column]
        }
        val correction = DoubleArray(STATE_SIZE) { row -> coordinates.indices.sumOf { gain[row, it] * residual[it] } }
        return inject(correction, gain, coordinates, variances)
    }

    private fun inject(correction: DoubleArray, gain: Matrix, coordinates: IntArray, variances: DoubleArray): Boolean {
        fun vector(offset: Int) = Vector3(correction[offset], correction[offset + 1], correction[offset + 2])
        val angle = vector(6)
        if (angle.norm() > MAX_CORRECTION_RAD) return false // local linearization no longer credible
        val residualTransform = Matrix.identity(STATE_SIZE)
        for (row in 0 until STATE_SIZE) for (column in coordinates.indices) residualTransform[row, coordinates[column]] -= gain[row, column]
        val measurementNoise = Matrix(coordinates.size, coordinates.size) { row, column -> if (row == column) variances[row] else 0.0 }
        val joseph = residualTransform * covariance * residualTransform.transpose() + gain * measurementNoise * gain.transpose()
        val reset = Matrix.identity(STATE_SIZE).apply { block(6, 6, Matrix.identity(3) + Matrix.skew(angle) * -0.5) }
        val updated = (reset * joseph * reset.transpose()).symmetric()
        if (!updated.finite() || updated.cholesky() == null) return false
        state = state.copy(
            position = state.position + vector(0),
            velocity = state.velocity + vector(3),
            attitude = (state.attitude * Attitude.exp(angle)).normalized(),
            accelerometerBias = state.accelerometerBias + vector(9),
            gyroscopeBias = state.gyroscopeBias + vector(12),
        )
        covariance = updated
        return true
    }

    private fun healthy() = state.position.isFinite() && state.velocity.isFinite() && covariance.finite() && (0 until STATE_SIZE).all { covariance[it, it] > 0.0 }

    companion object {
        const val GRAVITY_MPS2 = 9.80665
        private const val STATE_SIZE = 15
        private const val MAX_GAP_S = 0.25
        private const val MAX_STEP_S = 0.02
        private const val MAX_FORCE = 200.0
        private const val MAX_RATE = 35.0
        private const val MAX_CORRECTION_RAD = 0.5

        // 99.9% chi-square gates, indexed by measurement degrees of freedom minus one.
        private val INNOVATION_LIMITS = doubleArrayOf(10.828, 13.816, 16.266, 18.467, 20.515, 22.458)
    }
}
