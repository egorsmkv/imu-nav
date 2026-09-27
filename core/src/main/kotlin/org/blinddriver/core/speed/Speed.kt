package org.blinddriver.core.speed

import org.blinddriver.core.route.Hazard
import org.blinddriver.core.route.HazardKind
import org.blinddriver.core.route.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val MAX_SPEED_MPS = 150.0 / 3.6

/** A speed estimate with 1-sigma uncertainty. */
data class SpeedEstimate(val speedMps: Double, val sigmaMps: Double, val samples: Int, val spanS: Double)

/**
 * Inverse-variance fusion of up to three speed sources:
 *  - last GPS speed, sigma growing with its age (1.5 + 0.08 m/s per second),
 *  - route prior (speed limit × learned driver ratio), sigma 6 m/s,
 *  - network-derived speed, its own regression sigma (≥ 0.3).
 */
object SpeedFusion {
    private const val ROUTE_PRIOR_SIGMA = 6.0

    fun fuse(lastGpsSpeed: Double?, gpsAgeMs: Long, routePrior: Double?, network: SpeedEstimate?): Double {
        var wSum = 0.0
        var vSum = 0.0
        if (lastGpsSpeed != null) {
            val sigma = 1.5 + max(gpsAgeMs, 0L) / 1000.0 * 0.08
            val w = 1.0 / (sigma * sigma)
            wSum += w
            vSum += w * lastGpsSpeed
        }
        if (routePrior != null) {
            val w = 1.0 / (ROUTE_PRIOR_SIGMA * ROUTE_PRIOR_SIGMA)
            wSum += w
            vSum += w * routePrior
        }
        if (network != null) {
            val sigma = max(network.sigmaMps, 0.3)
            val w = 1.0 / (sigma * sigma)
            wSum += w
            vSum += w * network.speedMps
        }
        return if (wSum == 0.0) 0.0 else (vSum / wSum).coerceIn(0.0, MAX_SPEED_MPS)
    }
}

/**
 * Speed from cell/Wi-Fi fixes: the fixes are projected onto the route giving s(t); a weighted
 * least-squares line s = a + b·t (weights 1/max(acc,20)²) is fitted and the slope b is the speed.
 * Outliers beyond max(250 m, 3σ) are dropped and the line refitted.
 */
class NetSpeedEstimator {
    private class Sample(val t: Long, val s: Double, val acc: Double)
    private class Line(val intercept: Double, val slope: Double, val slopeSigma: Double, val t0: Long)

    private val samples = ArrayList<Sample>()

    fun clear() = samples.clear()

    fun add(s: Double, accM: Double, elapsedMs: Long) {
        if (samples.isEmpty() || elapsedMs > samples.last().t) {
            samples += Sample(elapsedMs, s, accM)
            while (samples.size > 60) samples.removeAt(0)
        }
    }

    /** Try short windows first for responsiveness, fall back to 90 s. */
    fun estimate(nowMs: Long): SpeedEstimate? =
        estimate(nowMs, 30_000, 15.0) ?: estimate(nowMs, 40_000, 20.0) ?: strictEstimate(nowMs)

    fun strictEstimate(nowMs: Long): SpeedEstimate? = estimate(nowMs, 90_000, 30.0)

    private fun estimate(nowMs: Long, windowMs: Long, minSpanS: Double): SpeedEstimate? {
        var window = samples.filter { nowMs - it.t <= windowMs }
        if (window.size < 4) return null
        var line = fit(window) ?: return null
        val inliers = window.filter {
            abs(it.s - (line.intercept + line.slope * (it.t - line.t0) / 1000.0)) <= max(250.0, max(it.acc, 20.0) * 3)
        }
        if (inliers.size < window.size && inliers.size >= 4) {
            line = fit(inliers) ?: return null
            window = inliers
        }
        val span = (window.last().t - window.first().t) / 1000.0
        if (span < minSpanS) return null
        val sigma = line.slopeSigma * 1.5
        if (sigma > 4.0) return null
        return SpeedEstimate(max(line.slope, 0.0), sigma, window.size, span)
    }

    private fun fit(points: List<Sample>): Line? {
        if (points.size < 2) return null
        val t0 = points.first().t
        var w = 0.0
        var wt = 0.0
        var ws = 0.0
        for (p in points) {
            val a = max(p.acc, 20.0)
            val wi = 1.0 / (a * a)
            w += wi
            wt += wi * (p.t - t0) / 1000.0
            ws += wi * p.s
        }
        val tMean = wt / w
        val sMean = ws / w
        var stt = 0.0
        var sts = 0.0
        for (p in points) {
            val a = max(p.acc, 20.0)
            val wi = 1.0 / (a * a)
            val dt = (p.t - t0) / 1000.0 - tMean
            stt += wi * dt * dt
            sts += wi * dt * (p.s - sMean)
        }
        if (stt <= 1e-9) return null
        val slope = sts / stt
        return Line(sMean - tMean * slope, slope, sqrt(1.0 / stt), t0)
    }
}

/** Persistence hook for [SpeedProfile]. */
interface SpeedProfileStore {
    fun load(): SpeedProfile.State
    fun save(state: SpeedProfile.State)

    object InMemory : SpeedProfileStore {
        private var state = SpeedProfile.State()
        override fun load() = state
        override fun save(state: SpeedProfile.State) {
            this.state = state
        }
    }
}

/**
 * Learns how fast this driver goes relative to the posted limit, separately for city
 * (limit < 70 km/h) and highway. Ratio samples are GPS speed / limit, clamped to [0.2, 2.5],
 * averaged with weight 1/(min(n,60)+1).
 */
class SpeedProfile(private val store: SpeedProfileStore = SpeedProfileStore.InMemory) {
    data class State(val cityRatio: Double = 0.8, val highwayRatio: Double = 0.8, val cityN: Int = 0, val highwayN: Int = 0)

    var state: State = store.load()
        private set

    fun ratioFor(limitKmh: Int): Double = if (limitKmh < 70) state.cityRatio else state.highwayRatio

    fun learn(gpsSpeedMps: Double, limitKmh: Int) {
        if (limitKmh <= 0 || gpsSpeedMps < 4.0) return
        val ratio = (gpsSpeedMps * 3.6 / limitKmh).coerceIn(0.2, 2.5)
        val s = state
        state = if (limitKmh < 70) {
            val a = 1.0 / (min(s.cityN, 60) + 1)
            s.copy(cityRatio = s.cityRatio + a * (ratio - s.cityRatio), cityN = s.cityN + 1)
        } else {
            val a = 1.0 / (min(s.highwayN, 60) + 1)
            s.copy(highwayRatio = s.highwayRatio + a * (ratio - s.highwayRatio), highwayN = s.highwayN + 1)
        }
        if ((state.cityN + state.highwayN) % 20 == 0) store.save(state)
    }
}

object RouteSpeedPrior {
    /** Ukrainian international / national / regional / territorial road references (М-06, Н-01, Р-02, Т-10...). */
    private val MAJOR_ROAD = Regex("(^|[\\s(,/])[МНРТEЕMHPT]-?\\d{1,2}")

    /**
     * Expected cruising speed at [s]: learned ratio × speed limit if the segment has one; else the
     * router's modelled segment speed; otherwise the average speed of the current step, clamped per road class.
     */
    fun at(route: Route, s: Double, profile: SpeedProfile): Double? {
        val segment = route.segmentAt(s)
        route.maxspeedAtSegment(segment)?.let { limit ->
            return limit / 3.6 * profile.ratioFor(limit)
        }
        route.modelledSpeedAtSegment(segment)?.let { return it }
        var current = -1
        for (i in route.steps.indices) {
            if (route.stepS(i) <= s) current = i else break
        }
        val step = route.steps.getOrNull(current) ?: route.steps.firstOrNull() ?: return null
        val major = MAJOR_ROAD.containsMatchIn(step.name) || step.distanceM >= 3000
        val kmh = if (step.durationS <= 0 || step.distanceM < 200) {
            if (major) 80.0 else 40.0
        } else {
            val avg = step.distanceM / step.durationS * 3.6
            if (major) avg.coerceIn(50.0, 110.0) else avg.coerceIn(15.0, 70.0)
        }
        return kmh / 3.6
    }
}

object SpeedPlan {
    /**
     * Speed cap near hazards: 3 m/s approaching a traffic signal (only when no network speed is
     * available to say otherwise), 5 m/s over traffic calming.
     */
    fun cap(hazards: List<Hazard>, s: Double, noNetworkSpeed: Boolean): Double? {
        var cap: Double? = null
        for (h in hazards) {
            val d = h.s - s
            when (h.kind) {
                HazardKind.TRAFFIC_SIGNAL -> if (noNetworkSpeed && d in -5.0..45.0) cap = min(cap ?: 3.0, 3.0)
                HazardKind.TRAFFIC_CALMING -> if (d in -6.0..20.0) cap = min(cap ?: 5.0, 5.0)
            }
        }
        return cap
    }
}
