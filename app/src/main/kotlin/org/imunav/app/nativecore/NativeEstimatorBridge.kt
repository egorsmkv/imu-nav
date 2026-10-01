package org.imunav.app.nativecore

import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.imu.TurnEvidence
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.MotionEvidence
import org.imunav.core.nav.RouteEstimate
import org.imunav.core.nav.RouteEstimateProvider
import org.imunav.core.route.TravelMode
import java.util.Locale

/**
 * Owns the Rust estimator used either in shadow mode or as the live navigation state owner.
 * Route geometry is prepared off the main thread by AppGraph before starting this bridge.
 */
class NativeEstimatorBridge(private val log: (String) -> Unit) : RouteEstimateProvider {
    private var estimator: NativeNavigationEstimator? = null
    private var lastLogMs = -LOG_EVERY_MS

    /** Live engine path: no second shadow tick, and only accepted GPS positions count as restored GPS. */
    override fun estimate(nowMs: Long, positioning: PositioningSnapshot, motion: MotionEvidence?, turn: TurnEvidence?): RouteEstimate? {
        val current = estimator ?: return null
        val state = try {
            current.tick(nowMs, positioning.lastUsableGps, motion, positioning.lastNet, turn)
        } catch (failure: IllegalStateException) {
            if (nowMs - lastLogMs >= LOG_EVERY_MS) {
                lastLogMs = nowMs
                log("native_estimator_unavailable reason=${failure.message}")
            }
            return null
        }
        return RouteEstimate(state.positionM, state.speedMps, state.safetyRadiusM, state.gpsPositionAccepted, state.gpsSpeedAccepted)
    }

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
    fun tick(nowMs: Long, guidance: GuidanceState, positioning: PositioningSnapshot, ignoreGps: Boolean = false, motion: MotionEvidence? = null, turn: TurnEvidence? = null) {
        val current = estimator ?: return
        val state = current.tick(nowMs, positioning.lastUsableGps.takeUnless { ignoreGps }, motion, positioning.lastNet, turn)

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
