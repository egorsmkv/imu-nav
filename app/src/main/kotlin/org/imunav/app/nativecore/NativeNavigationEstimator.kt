package org.imunav.app.nativecore

import org.imunav.core.gnss.JudgedFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.MotionEvidence
import org.imunav.core.route.TravelMode
import java.io.Closeable
import kotlin.math.sqrt

/** High-level owner for Rust prediction, projection, covariance updates, and drift accounting. */
class NativeNavigationEstimator private constructor(private var handle: Long) : Closeable {
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
    }

    fun tick(nowMs: Long, gps: JudgedFix?, motion: MotionEvidence? = null): State {
        val fix = gps?.fix
        val doubles = doubleArrayOf(
            fix?.lat ?: Double.NaN,
            fix?.lon ?: Double.NaN,
            fix?.accuracyM?.toDouble() ?: Double.NaN,
            fix?.speedMps?.toDouble() ?: Double.NaN,
            fix?.speedAccuracyMps?.toDouble() ?: Double.NaN,
            motion?.factor ?: Double.NaN,
            motion?.cruiseSpeedMps ?: Double.NaN,
        )
        val longs = longArrayOf(
            if (fix == null) 0 else 1,
            fix?.elapsedMs ?: 0,
            if (gps?.verdict?.level == TrustLevel.SUSPECT) TRUST_SUSPECT else TRUST_GOOD,
            if (motion == null) 0 else 1,
            motion?.validUntilMs ?: 0,
            if (motion?.networkMoving == true) 1 else 0,
        )
        val values = nativeTick(requireHandle(), nowMs, doubles, longs) ?: error("native navigation estimator tick failed")
        check(values.size == STATE_SIZE) { "native navigation estimator returned ${values.size} values" }
        return State(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
    }

    fun onVehicleSpeed(kmh: Double, elapsedMs: Long): Boolean = accepted(nativeOnVehicleSpeed(requireHandle(), kmh, elapsedMs))

    fun replaceRoute(route: NativeRouteGeometry, positionM: Double, positionSigmaM: Double) {
        checkResult(nativeReplaceRoute(requireHandle(), route.handle.also { check(it != 0L) { "native route geometry is closed" } }, positionM, positionSigmaM))
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        checkResult(nativeDestroy(current))
    }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "native navigation estimator is closed" } }

    private fun accepted(code: Int): Boolean = when (code) {
        RESULT_OK -> true
        RESULT_REJECTED -> false
        else -> error("native navigation estimator error=$code")
    }

    private fun checkResult(code: Int) {
        check(code == RESULT_OK) { "native navigation estimator error=$code" }
    }

    companion object {
        private const val RESULT_OK = 0
        private const val RESULT_REJECTED = 1
        private const val TRUST_GOOD = 0L
        private const val TRUST_SUSPECT = 1L
        private const val STATE_SIZE = 7

        init {
            System.loadLibrary("imu_nav_jni")
        }

        fun create(
            route: NativeRouteGeometry,
            positionM: Double,
            speedMps: Double,
            positionSigmaM: Double,
            speedSigmaMps: Double,
            systematicDriftM: Double,
            mode: TravelMode,
            nowMs: Long,
        ): NativeNavigationEstimator {
            val initial = doubleArrayOf(positionM, speedMps, positionSigmaM, speedSigmaMps, systematicDriftM)
            val modeCode = when (mode) {
                TravelMode.CAR -> MODE_CAR
                TravelMode.FOOT -> MODE_FOOT
            }
            val handle = nativeCreate(route.handle.also { check(it != 0L) { "native route geometry is closed" } }, initial, modeCode, nowMs)
            check(handle != 0L) { "could not create native navigation estimator" }
            return NativeNavigationEstimator(handle)
        }

        private const val MODE_CAR = 0
        private const val MODE_FOOT = 1

        @JvmStatic private external fun nativeCreate(routeHandle: Long, initialValues: DoubleArray, mode: Int, nowMs: Long): Long

        @JvmStatic private external fun nativeDestroy(handle: Long): Int

        @JvmStatic private external fun nativeOnVehicleSpeed(handle: Long, speedKmh: Double, elapsedMs: Long): Int

        @JvmStatic private external fun nativeReplaceRoute(handle: Long, routeHandle: Long, positionM: Double, positionSigmaM: Double): Int

        @JvmStatic private external fun nativeTick(handle: Long, nowMs: Long, doubles: DoubleArray, longs: LongArray): DoubleArray?
    }
}
