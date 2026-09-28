package org.blinddriver.core.record

import org.blinddriver.core.Tuning
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.ServiceArea
import org.blinddriver.core.gnss.FixSource
import org.blinddriver.core.gnss.PositioningHub
import org.blinddriver.core.gnss.RawFix
import org.blinddriver.core.gnss.TrustLevel
import org.blinddriver.core.nav.NavListener
import org.blinddriver.core.nav.NavigationEngine
import org.blinddriver.core.nav.PositionSource
import org.blinddriver.core.speed.SpeedProfile
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
    override fun toString() = if (count == 0) "n=0" else
        "n=$count mean=${meanM.roundToInt()} median=${medianM.roundToInt()} p95=${p95M.roundToInt()} max=${maxM.roundToInt()} rms=${rmsM.roundToInt()} m"

    companion object {
        fun of(values: List<Double>): ReplayStats {
            if (values.isEmpty()) return ReplayStats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val s = values.sorted()
            return ReplayStats(
                s.size, s.average(), s[s.size / 2], s[((s.size - 1) * 0.95).toInt()], s.last(),
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
    /** Along-track error at the end of each blind stretch. */
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
class TripReplayer(
    private val tuning: Tuning = Tuning.DEFAULT,
    private val area: ServiceArea = ServiceArea.EVERYWHERE,
) {
    fun replay(events: List<TripEvent>, hideGpsAfterS: Double? = null): ReplayResult {
        val sorted = events.sortedBy { it.elapsedMs }
        val log = ArrayList<String>()
        var clockOffset = 0L
        var nowElapsed = sorted.firstOrNull()?.elapsedMs ?: 0L
        val hub = PositioningHub(area = area, wallClock = { nowElapsed + clockOffset })
        val engine = NavigationEngine(
            tuning = { tuning },
            speedProfile = SpeedProfile(),
            listener = object : NavListener {
                override fun onLog(message: String) {
                    log += message
                }
            },
        )
        val samples = ArrayList<ReplaySample>()
        val truthTrack = ArrayList<GeoPoint>()
        val engineTrack = ArrayList<GeoPoint>()
        var navStartMs: Long? = null
        var blindFrom: Long? = null
        var pendingStart: TripEvent.Start? = null
        var lastTruth: RawFix? = null
        var nextTick = Long.MIN_VALUE

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

        for (e in sorted) {
            if (nextTick == Long.MIN_VALUE) nextTick = e.elapsedMs
            while (nextTick <= e.elapsedMs) {
                tick(nextTick)
                nextTick += NavigationEngine.TICK_MS
            }
            nowElapsed = e.elapsedMs
            when (e) {
                is TripEvent.Fix -> {
                    if (e.fix.source == FixSource.GPS && clockOffset == 0L) clockOffset = e.fix.timeMs - e.fix.elapsedMs
                    val verdict = hub.onFix(e.fix)
                    if (e.fix.source == FixSource.GPS && verdict?.level == TrustLevel.GOOD) lastTruth = e.fix
                }
                is TripEvent.Imu -> {
                    hub.onOrientation(e.sample.headingDeg, e.sample.yawRateDegS, e.elapsedMs)
                    engine.onImu(e.sample, hub.gyroBias.biasDegS)
                }
                is TripEvent.Gnss -> hub.onGnssStatus(e.visible, e.used, e.meanCn0Used, e.cn0SpreadUsed, e.meanCn0Visible, e.dualFrequencyUsed, e.elapsedMs)
                is TripEvent.Agc -> hub.onAgc(e.agcDb, e.elapsedMs)
                is TripEvent.Start -> pendingStart = e
                is TripEvent.RouteSet -> {
                    val start = pendingStart
                    if (engine.route == null && start != null) {
                        engine.start(e.route, start.destination, start.waypoints, e.elapsedMs, start.startAccuracyM)
                        navStartMs = e.elapsedMs
                    } else {
                        engine.setRoute(e.route, e.elapsedMs)
                    }
                }
                is TripEvent.Stop -> engine.stop()
                is TripEvent.Estimate -> Unit
            }
        }
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
