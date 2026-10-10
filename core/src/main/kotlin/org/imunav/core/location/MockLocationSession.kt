package org.imunav.core.location

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource

/** A navigation estimate ready for export, with its original monotonic sampling time. */
data class MockLocationSample(
    val point: GeoPoint,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val elapsedMs: Long,
) {
    companion object {
        /** Never export idle, missing or numerically invalid guidance as a plausible GPS fix. */
        fun from(state: GuidanceState, elapsedMs: Long): MockLocationSample? {
            val point = state.position ?: return null
            if (!state.active || state.source == PositionSource.NONE || elapsedMs < 0) return null
            if (point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0) return null
            if (!state.uncertaintyM.isFinite() || state.uncertaintyM < 0 || state.uncertaintyM > Float.MAX_VALUE) return null
            if (!state.speedKmh.isFinite() || state.speedKmh < 0 || !state.bearingDeg.isFinite()) return null
            return MockLocationSample(
                point, state.uncertaintyM.toFloat().coerceAtLeast(1f), state.speedKmh / 3.6f,
                ((state.bearingDeg % 360f) + 360f) % 360f, elapsedMs,
            )
        }
    }
}

/** Platform operations run serially on the caller's worker, never on the navigation thread. */
interface MockLocationProvider {
    fun isAllowed(): Boolean
    fun install()
    fun publish(sample: MockLocationSample)
    fun remove()
}

/** User-visible export state, distinct from the remembered opt-in setting. */
enum class MockLocationStatus { OFF, WAITING, ACTIVE, PERMISSION_REQUIRED, ERROR, CLEANUP_FAILED }

/**
 * Owns only the provider this session installed. Partial startup and publishing failures release it;
 * failed cleanup retains ownership so a later update can retry instead of reporting false success.
 */
class MockLocationSession(private val provider: MockLocationProvider, initiallyInstalled: Boolean = false) {
    private var installed = initiallyInstalled

    /** Permission is checked even while waiting, so settings can explain missing Android setup. */
    fun update(enabled: Boolean, sample: MockLocationSample?): MockLocationStatus {
        if (!enabled) return releaseOr(MockLocationStatus.OFF)
        return runCatching {
            when {
                !provider.isAllowed() -> releaseOr(MockLocationStatus.PERMISSION_REQUIRED)
                sample == null -> releaseOr(MockLocationStatus.WAITING)
                else -> {
                    if (!installed) {
                        provider.install()
                        installed = true
                    }
                    provider.publish(sample)
                    MockLocationStatus.ACTIVE
                }
            }
        }.getOrElse { failure ->
            releaseOr(if (failure is SecurityException) MockLocationStatus.PERMISSION_REQUIRED else MockLocationStatus.ERROR)
        }
    }

    private fun releaseOr(status: MockLocationStatus): MockLocationStatus {
        if (!installed) return status
        return runCatching {
            provider.remove()
            installed = false
            status
        }.getOrDefault(MockLocationStatus.CLEANUP_FAILED)
    }
}
