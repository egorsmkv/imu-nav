package org.imunav.app.nativecore

import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.MotionEvidence
import org.imunav.core.route.TravelMode
import java.util.Locale

/**
 * Runs the Rust estimator beside the established engine while recordings establish its tuning.
 *
 * The native estimate is deliberately observational during this migration stage: it receives only
 * measurements that already passed the trust classifier, and logs its disagreement with the live
 * engine. Once replay coverage is sufficient, this bridge becomes the engine's state owner without
 * changing the JNI contract or covariance implementation.
 */
class NativeEstimatorBridge(private val log: (String) -> Unit) {
    private var estimator: NativeNavigationEstimator? = null
    private var lastLogMs = -LOG_EVERY_MS

    fun start(route: NativeRouteGeometry, positionM: Double, speedMps: Double, positionSigmaM: Double, mode: TravelMode, nowMs: Long) {
        close()
        estimator = NativeNavigationEstimator.create(route, positionM, speedMps, positionSigmaM, INITIAL_SPEED_SIGMA_MPS, 0.0, mode, nowMs)
        lastLogMs = nowMs - LOG_EVERY_MS
        log("native_estimator active=true mode=$mode")
    }

    fun onVehicleSpeed(kmh: Double, elapsedMs: Long) {
        estimator?.onVehicleSpeed(kmh, elapsedMs)
    }

    /** Feed one post-engine snapshot; raw BAD GPS and deliberately simulated GPS loss are excluded. */
    fun tick(nowMs: Long, guidance: GuidanceState, positioning: PositioningSnapshot, ignoreGps: Boolean = false, motion: MotionEvidence? = null) {
        val current = estimator ?: return
        val state = current.tick(nowMs, positioning.lastUsableGps.takeUnless { ignoreGps }, motion)

        if (nowMs - lastLogMs >= LOG_EVERY_MS) {
            lastLogMs = nowMs
            log(
                "native_est s=${state.positionM.toInt()} ds=${(state.positionM - guidance.s).toInt()} " +
                    "v=${"%.1f".format(Locale.US, state.speedMps)} sigma=${state.positionSigmaM.toInt()} safety=${state.safetyRadiusM.toInt()}",
            )
        }
    }

    /** Keep route-coordinate changes such as reroutes from looking like statistical measurements. */
    fun replaceRoute(route: NativeRouteGeometry, positionM: Double, sigmaM: Double) {
        estimator?.replaceRoute(route, positionM, sigmaM)
    }

    fun close() {
        estimator?.close()
        estimator = null
    }

    private companion object {
        const val INITIAL_SPEED_SIGMA_MPS = 6.0
        const val LOG_EVERY_MS = 10_000L
    }
}
