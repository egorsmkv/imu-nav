package org.imunav.app.navigation

import android.content.Context
import android.os.SystemClock
import org.imunav.app.TripLog
import org.imunav.app.nativecore.NativeEstimatorBridge
import org.imunav.app.nativecore.NativeRouteGeometry
import org.imunav.app.nativecore.NativeRouteProjector
import org.imunav.app.obd.ObdLink
import org.imunav.app.service.NavService
import org.imunav.app.trips.TripManager
import org.imunav.app.voice.Voice
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import org.imunav.core.util.StartupTransaction

/** Commits or releases one navigation session in the order required by its resources. */
internal class NavigationSessionLifecycle(
    private val context: Context,
    private val tripLog: TripLog,
    private val routeProjector: NativeRouteProjector,
    private val estimatorBridge: NativeEstimatorBridge,
    private val engine: NavigationEngine,
    private val trips: TripManager,
    private val obd: ObdLink,
    private val voice: Voice,
    private val applyPower: () -> Unit,
    private val maybeAutoSync: () -> Unit,
) {
    /** Start the service before publishing a live engine, rolling resources back if any step fails. */
    fun start(
        prepared: PreparedResource<NativeRouteGeometry>,
        route: Route,
        destination: GeoPoint,
        startAccuracyM: Double,
        mode: TravelMode,
        initialSpeedAt: (Long) -> Double,
        selectedEstimator: NavigationEstimator,
    ) {
        StartupTransaction { tripLog.write("navigation_cleanup_failed ${it.message}") }.use { transaction ->
            transaction.acquire({ NavService.stop(context) }) { NavService.start(context) }
            transaction.acquire(tripLog::endTrip) {
                tripLog.startTrip()
                tripLog.write("start_accuracy=${startAccuracyM.toInt()}")
            }
            transaction.acquire(routeProjector::close) {
                routeProjector.install(route, prepared.value)
                prepared.transfer()
            }
            val now = SystemClock.elapsedRealtime()
            val initialSpeedMps = initialSpeedAt(now)
            transaction.acquire(estimatorBridge::close) { estimatorBridge.start(prepared.value, 0.0, initialSpeedMps, startAccuracyM, mode, now) }
            transaction.acquire(voice::stop) {}
            transaction.acquire(engine::stop) {
                engine.start(route, destination, nowMs = now, startAccuracyM = startAccuracyM, mode = mode, estimator = selectedEstimator)
            }
            transaction.acquire({ trips.end(arrived = false) }) { trips.begin(route, destination, emptyList(), startAccuracyM, mode) }
            if (mode == TravelMode.CAR) transaction.acquire(obd::stop, obd::start)
            applyPower()
            transaction.commit()
        }
    }

    /** Attempt every cleanup even if an earlier resource reports an error. */
    fun stop() {
        val arrived = engine.state.arrived
        val cleanup = listOf<() -> Unit>(
            { trips.end(arrived = arrived, keepShortTrip = true) }, engine::stop, voice::stop, estimatorBridge::close,
            routeProjector::close, obd::stop, applyPower, tripLog::endTrip,
            maybeAutoSync, { NavService.stop(context) },
        )
        cleanup.forEach { release -> runCatching(release).onFailure { tripLog.write("navigation_cleanup_failed ${it.message}") } }
    }
}
