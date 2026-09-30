package org.imunav.core.record

import org.imunav.core.Tuning
import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.NavListener
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.TravelMode
import org.imunav.core.speed.SpeedProfile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One comparison point: where the engine thought the car was vs. where GPS says it was. */
data class ReplaySample(
    val elapsedMs: Long,
    val engine: GeoPoint,
    val engineS: Double,
    val truth: GeoPoint,
    val truthS: Double,
    /** |engine − truth| along the route, m. */
    val alongErrorM: Double,
    /** Straight-line distance engine → truth, m. */
    val errorM: Double,
    /** How far the truth is from the route (large = car was off the planned route). */
    val truthOffRouteM: Double,
    val uncertaintyM: Double,
    val source: PositionSource,
    val blind: Boolean,
)

data class ReplayStats(val count: Int, val meanM: Double, val medianM: Double, val p95M: Double, val maxM: Double, val rmsM: Double) {
    override fun toString() = if (count == 0) {
        "n=0"
    } else {
        "n=$count mean=${meanM.roundToInt()} median=${medianM.roundToInt()} p95=${p95M.roundToInt()} max=${maxM.roundToInt()} rms=${rmsM.roundToInt()} m"
    }

    companion object {
        fun of(values: List<Double>): ReplayStats {
            if (values.isEmpty()) return ReplayStats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val s = values.sorted()
            return ReplayStats(
                s.size,
                s.average(),
                s[s.size / 2],
                s[((s.size - 1) * 0.95).toInt()],
                s.last(),
                sqrt(s.sumOf { it * it } / s.size),
            )
        }
    }
}

data class ReplayResult(
    val samples: List<ReplaySample>,
    val log: List<String>,
    val durationS: Double,
    val blindFromMs: Long?,
    /** Along-track error while GPS was hidden/unused (dead reckoning quality). */
    val blind: ReplayStats,
    /** Along-track error while GPS was used (sanity check; should be small). */
    val withGps: ReplayStats,
    /** Trusted GPS positions and the engine's positions at the same ticks (for maps). */
    val truthTrack: List<GeoPoint>,
    val engineTrack: List<GeoPoint>,
) {
    fun summary(): String = buildString {
        appendLine("duration ${(durationS / 60).roundToInt()} min, samples ${samples.size}")
        blindFromMs?.let { appendLine("GPS hidden from t=${it / 1000}s") }
        appendLine("along-track error, dead reckoning: $blind")
        appendLine("along-track error, with GPS:       $withGps")
        val snaps = log.count { it.startsWith("turn_snap") }
        val holds = log.count { it.startsWith("turn_hold ") }
        appendLine("turn holds $holds, turn snaps $snaps, reroute requests ${log.count { it.startsWith("reroute_from") }}")
    }
}

/**
 * Replays a recorded trip through the same pipeline the app runs (PositioningHub → trust classifier
 * → NavigationEngine, ticked every 500 ms) and compares the engine's position with GPS ground truth.
 *
 * @param hideGpsAfterS stop giving GPS to the engine this many seconds after navigation starts,
 *   to measure dead reckoning against the (still recorded) real track; null = replay as recorded.
 */
class TripReplayer(private val tuning: Tuning = Tuning.DEFAULT, private val area: ServiceArea = ServiceArea.EVERYWHERE) {
    fun replay(events: List<TripEvent>, hideGpsAfterS: Double? = null): ReplayResult {
        val sorted = events.sortedBy { it.elapsedMs }
        val session = Session(sorted.firstOrNull()?.elapsedMs ?: 0L, hideGpsAfterS)
        var nextTick = Long.MIN_VALUE
        for (e in sorted) {
            if (nextTick == Long.MIN_VALUE) nextTick = e.elapsedMs
            while (nextTick <= e.elapsedMs) {
                session.tick(nextTick)
                nextTick += NavigationEngine.TICK_MS
            }
            session.apply(e)
        }
        return session.result(sorted)
    }

    /** State of one replay run: the pipeline under test plus what has been measured so far. */
    private inner class Session(startElapsedMs: Long, private val hideGpsAfterS: Double?) {
        private var clockOffset = 0L
        private var nowElapsed = startElapsedMs
        private val log = ArrayList<String>()
        private val hub = PositioningHub(area = area, wallClock = { nowElapsed + clockOffset })
        private val engine = NavigationEngine(
            tuning = { tuning },
            speedProfile = SpeedProfile(),
            listener = object : NavListener {
                override fun onLog(message: String) {
                    log += message
                }
            },
        )
        private val samples = ArrayList<ReplaySample>()
        private val truthTrack = ArrayList<GeoPoint>()
        private val engineTrack = ArrayList<GeoPoint>()
        private var navStartMs: Long? = null
        private var blindFrom: Long? = null
        private var pendingStart: TripEvent.Start? = null
        private var pendingMode = TravelMode.CAR
        private var lastTruth: RawFix? = null

        fun tick(t: Long) {
            nowElapsed = t
            val start = navStartMs ?: return
            if (hideGpsAfterS != null && !engine.simulateGpsLoss && t - start >= hideGpsAfterS * 1000) {
                engine.simulateGpsLoss = true
                blindFrom = t
            }
            engine.tick(t, hub.snapshot(t))
            val st = engine.state
            val route = st.route ?: return
            val truth = lastTruth?.takeIf { t - it.elapsedMs <= 1500 } ?: return
            val pos = st.position ?: return
            val proj = route.project(truth.point, st.s, 400.0, 3000.0, 150.0)
            samples += ReplaySample(
                t, pos, st.s, truth.point, proj.s, abs(st.s - proj.s), Geo.distance(pos, truth.point), proj.offsetM,
                st.uncertaintyM, st.source, engine.simulateGpsLoss || !st.source.isGps,
            )
            truthTrack += truth.point
            engineTrack += pos
        }

        fun apply(e: TripEvent) {
            nowElapsed = e.elapsedMs
            when (e) {
                is TripEvent.Fix -> onFix(e.fix)

                is TripEvent.Imu -> {
                    hub.onOrientation(e.sample.headingDeg, e.sample.yawRateDegS, e.elapsedMs)
                    engine.onImu(e.sample, hub.gyroBias.biasDegS)
                }

                is TripEvent.Gnss -> hub.onGnssStatus(e.visible, e.used, e.meanCn0Used, e.cn0SpreadUsed, e.meanCn0Visible, e.dualFrequencyUsed, e.elapsedMs)

                is TripEvent.Agc -> hub.onAgc(e.agcDb, e.elapsedMs)

                is TripEvent.Start -> {
                    pendingStart = e
                    pendingMode = TravelMode.CAR // old recordings have no Mode event
                }

                is TripEvent.Mode -> pendingMode = e.mode

                is TripEvent.StepTaken -> engine.onStep(e.elapsedMs)

                is TripEvent.VehicleSpeed -> engine.onVehicleSpeed(e.kmh.toDouble(), e.elapsedMs)

                is TripEvent.Pressure -> engine.onPressure(e.hPa.toDouble(), e.elapsedMs)

                is TripEvent.RouteSet -> onRoute(e)

                is TripEvent.Stop -> engine.stop()

                is TripEvent.Estimate -> Unit
            }
        }

        private fun onFix(fix: RawFix) {
            if (fix.source == FixSource.GPS && clockOffset == 0L) clockOffset = fix.timeMs - fix.elapsedMs
            val verdict = hub.onFix(fix)
            // Ground truth = GPS fixes the classifier itself trusts.
            if (fix.source == FixSource.GPS && verdict?.level == TrustLevel.GOOD) lastTruth = fix
        }

        private fun onRoute(e: TripEvent.RouteSet) {
            val start = pendingStart
            if (engine.route == null && start != null) {
                engine.start(e.route, start.destination, start.waypoints, e.elapsedMs, start.startAccuracyM, pendingMode)
                navStartMs = e.elapsedMs
            } else {
                engine.setRoute(e.route, e.elapsedMs)
            }
        }

        fun result(sorted: List<TripEvent>): ReplayResult {
            val first = sorted.firstOrNull()?.elapsedMs ?: 0L
            val last = sorted.lastOrNull()?.elapsedMs ?: 0L
            // Only compare while the car was on the planned route; off-route stretches are reroutes, not DR error.
            val onRoute = samples.filter { it.truthOffRouteM < 60.0 }
            return ReplayResult(
                samples = samples,
                log = log,
                durationS = (last - first) / 1000.0,
                blindFromMs = blindFrom?.let { it - (navStartMs ?: it) },
                blind = ReplayStats.of(onRoute.filter { it.blind }.map { it.alongErrorM }),
                withGps = ReplayStats.of(onRoute.filter { !it.blind }.map { it.alongErrorM }),
                truthTrack = truthTrack,
                engineTrack = engineTrack,
            )
        }
    }
}
