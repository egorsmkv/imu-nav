package org.imunav.replay

import org.imunav.app.nativecore.NativeNavigationEstimator
import org.imunav.app.nativecore.NativeRouteGeometry
import org.imunav.core.Tuning
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.record.ReplayStats
import org.imunav.core.record.TripEvent
import org.imunav.core.route.TravelMode
import java.io.Closeable
import java.util.Locale
import kotlin.math.abs

/** Paired errors at a reference GPS timestamp; reference fixes never feed a blind estimator. */
data class ComparisonSample(
    val elapsedMs: Long,
    val blind: Boolean,
    val truthS: Double,
    val truthOffsetM: Double,
    val kotlinS: Double,
    val nativeS: Double,
    val nativeSigmaM: Double,
    val nativeSafetyM: Double,
) {
    val kotlinErrorM: Double get() = abs(kotlinS - truthS)
    val nativeErrorM: Double get() = abs(nativeS - truthS)
}

/** Paired on-route statistics; off-route observations remain in CSV for inspection. */
data class ComparisonResult(val samples: List<ComparisonSample>) {
    val blindSamples: List<ComparisonSample> get() = samples.filter { it.blind && it.truthOffsetM < MAX_TRUTH_OFFSET_M }
    val kotlinBlind: ReplayStats get() = ReplayStats.of(blindSamples.map { it.kotlinErrorM })
    val nativeBlind: ReplayStats get() = ReplayStats.of(blindSamples.map { it.nativeErrorM })

    /** Reports identical reference samples for both estimators and empirical uncertainty coverage. */
    fun summary(): String = buildString {
        appendLine("paired comparison at trusted GPS timestamps (reference GPS is not survey ground truth)")
        for (blind in listOf(false, true)) {
            val paired = samples.filter { it.blind == blind && it.truthOffsetM < MAX_TRUTH_OFFSET_M }
            appendLine("${if (blind) "GPS hidden" else "GPS available"}, Kotlin: ${ReplayStats.of(paired.map { it.kotlinErrorM })}")
            appendLine("${if (blind) "GPS hidden" else "GPS available"}, native: ${ReplayStats.of(paired.map { it.nativeErrorM })}")
            if (paired.isNotEmpty()) {
                val covered = paired.count { it.nativeErrorM <= it.nativeSafetyM }
                appendLine("native safety-radius coverage: $covered/${paired.size}")
            }
        }
        appendLine("off-route reference samples excluded: ${samples.count { it.truthOffsetM >= MAX_TRUTH_OFFSET_M }}")
    }

    /** Keeps signed positions and off-route reference samples available for independent analysis. */
    fun csv(): String = buildString {
        appendLine("elapsed_ms,blind,truth_s,truth_offset_m,kotlin_s,native_s,kotlin_error_m,native_error_m,native_sigma_m,native_safety_m")
        for (sample in samples) {
            appendLine(
                String.format(
                    Locale.US, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                    sample.elapsedMs, if (sample.blind) 1 else 0, sample.truthS, sample.truthOffsetM,
                    sample.kotlinS, sample.nativeS, sample.kotlinErrorM, sample.nativeErrorM, sample.nativeSigmaM, sample.nativeSafetyM,
                ),
            )
        }
    }

    private companion object {
        const val MAX_TRUTH_OFFSET_M = 60.0
    }
}

/**
 * Replays the Kotlin engine and the app's actual JNI estimator on a shared input timeline.
 * A separate hub judges reference GPS. After the cutoff, GPS never reaches the navigation hub,
 * including its gyro-bias learner. Scoring uses fresh reference timestamps, without extrapolation.
 */
class NativeComparison(
    private val tuning: Tuning = Tuning.DEFAULT,
    private val area: ServiceArea = ServiceArea.EVERYWHERE,
    private val nativeMotionEnabled: Boolean = true,
    private val nativeNetworkEnabled: Boolean = true,
    private val nativeTurnsEnabled: Boolean = true,
    private val nativeNetworkSpeedEnabled: Boolean = false,
) {
    /** Groups equal-time inputs before ticking and never uses a later GPS position to score a tick. */
    fun replay(events: List<TripEvent>, hideGpsAfterS: Double? = null): ComparisonResult {
        require(hideGpsAfterS == null || (hideGpsAfterS.isFinite() && hideGpsAfterS >= 0.0))
        return Session(hideGpsAfterS).use { session ->
            val sorted = events.sortedBy { it.elapsedMs }
            var nextTick = sorted.firstOrNull()?.elapsedMs ?: 0L
            for ((index, event) in sorted.withIndex()) {
                while (nextTick < event.elapsedMs) {
                    session.tick(nextTick)
                    nextTick += NavigationEngine.TICK_MS
                }
                session.apply(event)
                if (sorted.getOrNull(index + 1)?.elapsedMs != event.elapsedMs) {
                    // Score after all inputs with this timestamp, so neither estimator sees future data.
                    if (nextTick == event.elapsedMs || session.hasReferenceAt(event.elapsedMs)) session.tick(event.elapsedMs)
                    if (nextTick == event.elapsedMs) nextTick += NavigationEngine.TICK_MS
                }
            }
            ComparisonResult(session.samples.toList())
        }
    }

    /** Owns native handles for one run, including cleanup after malformed recordings. */
    private inner class Session(private val hideGpsAfterS: Double?) : Closeable {
        private var nowMs = 0L
        private var clockOffsetMs: Long? = null
        private val hub = PositioningHub(area = area, wallClock = { nowMs + (clockOffsetMs ?: 0L) })
        private val reference = PositioningHub(area = area, wallClock = { nowMs + (clockOffsetMs ?: 0L) })
        private val engine = NavigationEngine(tuning = { tuning }, listener = object : NavListener {})
        private var native: NativeNavigationEstimator? = null
        private var start: TripEvent.Start? = null
        private var navStartMs: Long? = null
        private var mode = TravelMode.CAR
        val samples = ArrayList<ComparisonSample>()

        private fun blind(timeMs: Long): Boolean = hideGpsAfterS?.let { after -> navStartMs?.let { timeMs - it >= after * 1000 } } == true

        fun hasReferenceAt(timeMs: Long): Boolean = reference.lastGood?.elapsedMs == timeMs

        /** Feeds both navigation paths the same available inputs while retaining a separate reference. */
        fun apply(event: TripEvent) {
            nowMs = event.elapsedMs
            when (event) {
                is TripEvent.Start -> {
                    close()
                    engine.stop()
                    start = event
                    navStartMs = null
                    mode = TravelMode.CAR
                }

                is TripEvent.Mode -> mode = event.mode

                is TripEvent.RouteSet -> installRoute(event)

                is TripEvent.Stop -> {
                    close()
                    engine.stop()
                    navStartMs = null
                }

                is TripEvent.Fix -> {
                    val fix = event.fix
                    if (fix.source == FixSource.GPS && clockOffsetMs == null) clockOffsetMs = fix.timeMs - fix.elapsedMs
                    reference.onFix(fix)
                    if (fix.source != FixSource.GPS || !blind(event.elapsedMs)) hub.onFix(fix)
                }

                is TripEvent.Imu -> {
                    reference.onOrientation(event.sample.headingDeg, event.sample.yawRateDegS, event.elapsedMs)
                    hub.onOrientation(event.sample.headingDeg, event.sample.yawRateDegS, event.elapsedMs)
                    engine.onImu(event.sample, hub.gyroBias.biasDegS)
                }

                is TripEvent.Gnss -> {
                    for (target in listOf(hub, reference)) {
                        target.onGnssStatus(event.visible, event.used, event.meanCn0Used, event.cn0SpreadUsed, event.meanCn0Visible, event.dualFrequencyUsed, event.elapsedMs)
                    }
                }

                is TripEvent.Agc -> {
                    hub.onAgc(event.agcDb, event.elapsedMs)
                    reference.onAgc(event.agcDb, event.elapsedMs)
                }

                is TripEvent.VehicleSpeed -> {
                    engine.onVehicleSpeed(event.kmh.toDouble(), event.elapsedMs)
                    native?.onVehicleSpeed(event.kmh.toDouble(), event.elapsedMs)
                }

                is TripEvent.Pressure -> engine.onPressure(event.hPa.toDouble(), event.elapsedMs)

                is TripEvent.StepTaken -> engine.onStep(event.elapsedMs)

                is TripEvent.Estimate -> Unit
            }
        }

        /** Re-anchors both algorithms on recorded reroutes without borrowing a reference position. */
        private fun installRoute(event: TripEvent.RouteSet) {
            NativeRouteGeometry.create(event.route).use { geometry ->
                val pending = start ?: return
                if (native == null) {
                    navStartMs = event.elapsedMs
                    engine.start(event.route, pending.destination, pending.waypoints, event.elapsedMs, pending.startAccuracyM, mode)
                    engine.simulateGpsLoss = false
                    val speed = hub.lastGood?.speedMps?.toDouble()?.takeUnless { blind(event.elapsedMs) } ?: 0.0
                    native = NativeNavigationEstimator.create(
                        geometry, 0.0, speed, pending.startAccuracyM, 6.0, 0.0, mode, event.elapsedMs,
                        networkSpeedEnabled = nativeNetworkSpeedEnabled,
                    )
                } else {
                    engine.setRoute(event.route, event.elapsedMs)
                    native?.replaceRoute(geometry, engine.progressS, engine.state.uncertaintyM)
                }
            }
        }

        /** Scores only when reference and prediction times agree, avoiding stale-GPS error. */
        fun tick(timeMs: Long) {
            nowMs = timeMs
            val estimator = native ?: return
            val hidden = blind(timeMs)
            engine.simulateGpsLoss = hidden
            val snapshot = hub.snapshot(timeMs)
            engine.tick(timeMs, snapshot)
            val motion = if (nativeMotionEnabled) engine.motionEvidence(timeMs) else null
            val network = snapshot.lastNet.takeIf { nativeNetworkEnabled }
            val turn = if (nativeTurnsEnabled) engine.turnEvidence(timeMs) else null
            val estimate = estimator.tick(timeMs, snapshot.lastUsableGps.takeUnless { hidden }, motion, network, turn)
            val truth = reference.lastGood?.takeIf { it.elapsedMs == timeMs } ?: return
            val route = engine.route ?: return
            // Global projection avoids favouring either estimator's route position when scoring.
            val projected = route.project(truth.point, 0.0, 0.0, route.length, 0.0)
            samples += ComparisonSample(timeMs, hidden, projected.s, projected.offsetM, engine.progressS, estimate.positionM, estimate.positionSigmaM, estimate.safetyRadiusM)
        }

        override fun close() {
            native?.close()
            native = null
        }
    }
}
