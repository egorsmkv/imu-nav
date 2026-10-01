package org.imunav.app.nativecore

import org.imunav.core.speed.SpeedEstimate
import org.imunav.core.speed.SpeedFusionProvider

/** Rust inverse-variance fusion for GNSS, route-prior and network-derived speed. */
object NativeSpeedFusion : SpeedFusionProvider {
    init {
        System.loadLibrary("imu_nav_jni")
    }

    override fun fuse(lastGpsSpeed: Double?, gpsAgeMs: Long, routePrior: Double?, network: SpeedEstimate?): Double = nativeFuse(
        lastGpsSpeed ?: Double.NaN,
        gpsAgeMs,
        routePrior ?: Double.NaN,
        network?.speedMps ?: Double.NaN,
        network?.sigmaMps ?: Double.NaN,
        network?.samples ?: 0,
        network?.spanS ?: 0.0,
    )

    @JvmStatic
    private external fun nativeFuse(
        lastGpsSpeedMps: Double,
        gpsAgeMs: Long,
        routePriorMps: Double,
        networkSpeedMps: Double,
        networkSigmaMps: Double,
        networkSamples: Int,
        networkSpanS: Double,
    ): Double
}
