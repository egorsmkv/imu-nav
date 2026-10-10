package org.imunav.core.imu.eskf

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Local right-multiplicative error dynamics for [position, velocity, attitude, accel bias, gyro bias]. */
internal fun inertialErrorDynamics(rotation: Matrix, force: Vector3, rate: Vector3) = Matrix(15, 15).apply {
    block(0, 3, Matrix.identity(3))
    block(3, 6, (rotation * Matrix.skew(force)) * -1.0)
    block(3, 9, rotation * -1.0)
    block(6, 6, Matrix.skew(rate) * -1.0)
    block(6, 12, Matrix.identity(3) * -1.0)
}

/** Derivative of Log(Exp(-angle) Exp(angle + error)) at zero error, used after injection. */
internal fun attitudeResetJacobian(angle: Vector3): Matrix {
    val magnitude = angle.norm()
    val squared = magnitude * magnitude
    // Stable limits avoid subtracting nearly equal floating-point numbers near zero.
    val first = if (magnitude < 1e-3) 0.5 - squared / 24 + squared * squared / 720 else (1 - cos(magnitude)) / squared
    val second = if (magnitude < 1e-3) 1.0 / 6 - squared / 120 + squared * squared / 5040 else (magnitude - sin(magnitude)) / (squared * magnitude)
    val cross = Matrix.skew(angle)
    return Matrix.identity(3) + cross * -first + (cross * cross) * second
}

/** Noise densities and standard deviations must remain positive and finite when stored as variances. */
internal fun hasFinitePositiveSquare(value: Double): Boolean {
    val squared = value * value
    return value > 0.0 && squared > 0.0 && squared.isFinite()
}

/**
 * Largest standard deviation of a finite positive-semidefinite 2-D covariance.
 * Scaling before the eigensolve avoids overflow in the trace/discriminant and underflow in their
 * squares. Take the square roots before restoring scale: the eigenvalue itself may exceed Double.
 */
internal fun horizontalPositionSigma(east: Double, north: Double, cross: Double): Double {
    val scale = maxOf(east, north)
    if (scale == 0.0) return 0.0
    val scaledEast = east / scale
    val scaledNorth = north / scale
    val scaledCross = cross / scale
    val eigenvalue = (scaledEast + scaledNorth + hypot(scaledEast - scaledNorth, 2.0 * scaledCross)) / 2.0
    return sqrt(scale) * sqrt(eigenvalue)
}

/** Immutable three-vector; body axes follow Android, navigation axes are east/north/up. */
data class Vector3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vector3(x * scale, y * scale, z * scale)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    internal fun values() = doubleArrayOf(x, y, z)

    internal fun cross(other: Vector3) = Vector3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)

    companion object {
        val ZERO = Vector3(0.0, 0.0, 0.0)
    }
}

/** Hamilton, scalar-first quaternion rotating device/body vectors into east/north/up. Angles are radians. */
data class Attitude(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Attitude {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        require(norm.isFinite() && norm > 1e-12) { "invalid attitude" }
        return Attitude(w / norm, x / norm, y / norm, z / norm)
    }

    operator fun times(other: Attitude) = Attitude(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    /** Rotation without constructing a temporary matrix; this quaternion must be normalized. */
    fun rotate(vector: Vector3): Vector3 {
        val imaginary = Vector3(x, y, z)
        val twiceCross = imaginary.cross(vector) * 2.0
        return vector + twiceCross * w + imaginary.cross(twiceCross)
    }

    internal fun matrix(): Matrix {
        val columns = listOf(rotate(Vector3(1.0, 0.0, 0.0)), rotate(Vector3(0.0, 1.0, 0.0)), rotate(Vector3(0.0, 0.0, 1.0)))
        return Matrix(3, 3) { row, column -> columns[column].values()[row] }
    }

    companion object {
        val IDENTITY = Attitude(1.0, 0.0, 0.0, 0.0)

        /** Exponential map for a local rotation vector, with a stable small-angle limit. */
        fun exp(rotation: Vector3): Attitude {
            val angle = rotation.norm()
            val scale = if (angle < 1e-8) 0.5 - angle * angle / 48.0 else sin(angle / 2.0) / angle
            return Attitude(cos(angle / 2.0), rotation.x * scale, rotation.y * scale, rotation.z * scale)
        }
    }
}

/** Small dense matrices owned by the filter; no mutable arrays escape in published estimates. */
internal class Matrix(val rows: Int, val columns: Int, init: (Int, Int) -> Double = { _, _ -> 0.0 }) {
    private val data = DoubleArray(rows * columns) { init(it / columns, it % columns) }
    operator fun get(row: Int, column: Int): Double = data[row * columns + column]
    operator fun set(row: Int, column: Int, value: Double) {
        data[row * columns + column] = value
    }
    operator fun plus(other: Matrix) = Matrix(rows, columns) { row, column -> this[row, column] + other[row, column] }
    operator fun times(scale: Double) = Matrix(rows, columns) { row, column -> this[row, column] * scale }
    fun transpose() = Matrix(columns, rows) { row, column -> this[column, row] }

    operator fun times(other: Matrix): Matrix {
        require(columns == other.rows)
        val result = Matrix(rows, other.columns)
        for (row in 0 until rows) {
            for (inner in 0 until columns) {
                val coefficient = this[row, inner]
                if (coefficient == 0.0) continue
                for (column in 0 until other.columns) result[row, column] += coefficient * other[inner, column]
            }
        }
        return result
    }

    fun block(row: Int, column: Int, block: Matrix) {
        for (i in 0 until block.rows) for (j in 0 until block.columns) this[row + i, column + j] = block[i, j]
    }

    /** Remove floating-point asymmetry, not negative variances or other evidence of filter failure. */
    fun symmetric() = Matrix(rows, columns) { row, column ->
        val forward = this[row, column]
        val reverse = this[column, row]
        val sum = forward + reverse
        // Preserve subnormal entries on the usual path; halve first only when the sum overflows.
        if (sum.isFinite()) sum * 0.5 else forward * 0.5 + reverse * 0.5
    }
    fun finite() = data.all { it.isFinite() }

    /** Cholesky factorization fails closed when a covariance is not positive definite. */
    fun cholesky(): Matrix? {
        val lower = Matrix(rows, rows)
        for (row in 0 until rows) {
            for (column in 0..row) {
                var residual = this[row, column]
                for (inner in 0 until column) residual -= lower[row, inner] * lower[column, inner]
                if (row == column) {
                    if (!residual.isFinite() || residual <= 0.0) return null
                    lower[row, column] = sqrt(residual)
                } else {
                    lower[row, column] = residual / lower[column, column]
                }
            }
        }
        return lower
    }

    /** Solve A x = b from A's lower Cholesky factor, avoiding an explicit matrix inverse. */
    fun solveCholesky(rhs: DoubleArray): DoubleArray {
        val result = rhs.copyOf()
        for (row in 0 until rows) {
            for (column in 0 until row) result[row] -= this[row, column] * result[column]
            result[row] /= this[row, row]
        }
        for (row in rows - 1 downTo 0) {
            for (column in row + 1 until rows) result[row] -= this[column, row] * result[column]
            result[row] /= this[row, row]
        }
        return result
    }

    companion object {
        fun identity(size: Int) = Matrix(size, size) { row, column -> if (row == column) 1.0 else 0.0 }
        fun skew(vector: Vector3) = Matrix(3, 3).apply {
            this[0, 1] = -vector.z
            this[0, 2] = vector.y
            this[1, 0] = vector.z
            this[1, 2] = -vector.x
            this[2, 0] = -vector.y
            this[2, 1] = vector.x
        }
    }
}
