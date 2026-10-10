package org.imunav.app.nativecore

import java.io.Closeable
import kotlin.math.sqrt

/**
 * Android owner for the Rust route-state estimator.
 *
 * Instances are thread-confined: navigation creates and uses them on the main thread. The native
 * handle is an opaque registry ID, not a pointer. All inputs are checked on both sides of JNI.
 */
class NativeRouteFilter private constructor(private var handle: Long) : Closeable {
    /** Result of projecting a geographic observation onto the installed route. */
    data class Projection(val positionM: Double, val offsetM: Double, val segment: Int, val latitudeDeg: Double, val longitudeDeg: Double)

    /** Immutable copy of the native state and covariance. */
    data class State(
        val positionM: Double,
        val speedMps: Double,
        val positionVarianceM2: Double,
        val positionSpeedCovariance: Double,
        val speedVarianceMps2: Double,
        val systematicDriftM: Double,
        val safetyRadiusM: Double,
    ) {
        val positionSigmaM: Double get() = sqrt(positionVarianceM2)
        val speedSigmaMps: Double get() = sqrt(speedVarianceMps2)
    }

    /** Predict with a continuous acceleration noise density (m/s/sqrt(s)), independent of callback rate. */
    fun predict(dtS: Double, accelerationNoiseMpsSqrtS: Double, systematicDriftPerM: Double) {
        checkResult(nativePredict(requireHandle(), dtS, accelerationNoiseMpsSqrtS, systematicDriftPerM))
    }

    fun installRoute(route: NativeRouteGeometry) {
        checkResult(nativeInstallRoute(requireHandle(), route.handle.also { check(it != 0L) { "native route geometry is closed" } }))
    }

    fun project(latitudeDeg: Double, longitudeDeg: Double, aroundM: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection {
        val values = nativeProject(requireHandle(), latitudeDeg, longitudeDeg, aroundM, behindM, aheadM, globalIfFartherM)
            ?: error("native route projection failed")
        check(values.size == PROJECTION_SIZE) { "native route projection returned ${values.size} values" }
        return Projection(values[0], values[1], values[2].toInt(), values[3], values[4])
    }

    /** @return false when the statistical innovation gate rejected the position. */
    fun updatePosition(positionM: Double, sigmaM: Double, nisGate: Double): Boolean = accepted(nativeUpdatePosition(requireHandle(), positionM, sigmaM, nisGate))

    /** @return false when the statistical innovation gate rejected the speed. */
    fun updateSpeed(speedMps: Double, sigmaMps: Double, nisGate: Double): Boolean = accepted(nativeUpdateSpeed(requireHandle(), speedMps, sigmaMps, nisGate))

    /** Install a trusted route landmark and reset accumulated systematic drift. */
    fun anchorPosition(positionM: Double, sigmaM: Double) {
        checkResult(nativeAnchorPosition(requireHandle(), positionM, sigmaM))
    }

    /** Clear the conservative bias allowance after an independent trusted position update. */
    fun resetSystematicDrift() {
        checkResult(nativeResetSystematicDrift(requireHandle()))
    }

    fun state(): State {
        val values = nativeGetState(requireHandle()) ?: error("native route filter failed to return state")
        check(values.size == STATE_SIZE) { "native route filter returned ${values.size} values" }
        return State(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        checkResult(nativeDestroy(current))
    }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "native route filter is closed" } }

    private fun accepted(code: Int): Boolean = when (code) {
        RESULT_OK -> true
        RESULT_REJECTED -> false
        else -> throw IllegalArgumentException("native route filter error=$code")
    }

    private fun checkResult(code: Int) {
        check(code == RESULT_OK) { "native route filter error=$code" }
    }

    companion object {
        private const val RESULT_OK = 0
        private const val RESULT_REJECTED = 1
        private const val STATE_SIZE = 7
        private const val PROJECTION_SIZE = 5

        init {
            System.loadLibrary("imu_nav_jni")
        }

        fun create(positionM: Double, speedMps: Double, positionSigmaM: Double, speedSigmaMps: Double, systematicDriftM: Double = 0.0): NativeRouteFilter {
            val handle = nativeCreate(positionM, speedMps, positionSigmaM, speedSigmaMps, systematicDriftM)
            check(handle != 0L) { "could not create native route filter" }
            return NativeRouteFilter(handle)
        }

        @JvmStatic
        private external fun nativeCreate(positionM: Double, speedMps: Double, positionSigmaM: Double, speedSigmaMps: Double, systematicDriftM: Double): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long): Int

        @JvmStatic
        private external fun nativePredict(handle: Long, dtS: Double, accelerationNoiseMpsSqrtS: Double, systematicDriftPerM: Double): Int

        @JvmStatic
        private external fun nativeInstallRoute(handle: Long, routeHandle: Long): Int

        @JvmStatic
        private external fun nativeProject(
            handle: Long,
            latitudeDeg: Double,
            longitudeDeg: Double,
            aroundM: Double,
            behindM: Double,
            aheadM: Double,
            globalIfFartherM: Double,
        ): DoubleArray?

        @JvmStatic
        private external fun nativeUpdatePosition(handle: Long, positionM: Double, sigmaM: Double, nisGate: Double): Int

        @JvmStatic
        private external fun nativeUpdateSpeed(handle: Long, speedMps: Double, sigmaMps: Double, nisGate: Double): Int

        @JvmStatic
        private external fun nativeAnchorPosition(handle: Long, positionM: Double, sigmaM: Double): Int

        @JvmStatic
        private external fun nativeResetSystematicDrift(handle: Long): Int

        @JvmStatic
        private external fun nativeGetState(handle: Long): DoubleArray?
    }
}
