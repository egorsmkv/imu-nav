package org.imunav.core.speed

import org.imunav.core.route.Hazard
import org.imunav.core.route.HazardKind
import org.imunav.core.route.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** No estimate may exceed 150 km/h. */
const val MAX_SPEED_MPS = 150.0 / 3.6

/**
 * A speed with its uncertainty.
 * @param sigmaMps one standard deviation: the true speed is usually within ± this
 * @param samples how many measurements it is based on
 * @param spanS how many seconds those measurements cover
 */
data class SpeedEstimate(val speedMps: Double, val sigmaMps: Double, val samples: Int, val spanS: Double)

/** Speed-fusion math boundary; Android uses Rust while JVM replay uses [SpeedFusion]. */
fun interface SpeedFusionProvider {
    fun fuse(lastGpsSpeed: Double?, gpsAgeMs: Long, routePrior: Double?, network: SpeedEstimate?): Double
}

/**
 * Combines up to three speed guesses into one.
 *
 * Each source gets a weight of 1 / σ², where σ is how uncertain it is ("inverse-variance
 * weighting"): a source twice as precise counts four times as much. The sources are:
 *  - the last GPS speed — very good when fresh, less so as it ages (σ = 1.5 m/s + 0.08 m/s per second),
 *  - the route prior (speed limit × how fast this driver usually goes), σ = 6 m/s,
 *  - speed derived from network/cell fixes ([NetSpeedEstimator]), with its own σ (at least 0.3 m/s).
 */
object SpeedFusion : SpeedFusionProvider {
    private const val ROUTE_PRIOR_SIGMA = 6.0
    private const val GPS_SIGMA = 1.5
    private const val GPS_SIGMA_PER_S = 0.08
    private const val MIN_NETWORK_SIGMA = 0.3

    /** Fused speed in m/s; invalid sources are ignored and unusable arithmetic returns 0. */
    override fun fuse(lastGpsSpeed: Double?, gpsAgeMs: Long, routePrior: Double?, network: SpeedEstimate?): Double {
        val sources = buildList {
            if (lastGpsSpeed != null && lastGpsSpeed.isFinite() && lastGpsSpeed >= 0.0) add(lastGpsSpeed to GPS_SIGMA + max(gpsAgeMs, 0L) / 1000.0 * GPS_SIGMA_PER_S)
            if (routePrior != null && routePrior.isFinite() && routePrior >= 0.0) add(routePrior to ROUTE_PRIOR_SIGMA)
            if (network != null && validNetworkEstimate(network)) {
                add(network.speedMps to max(network.sigmaMps, MIN_NETWORK_SIGMA))
            }
        }
        if (sources.isEmpty()) return 0.0
        val weightSum = sources.sumOf { (_, sigma) -> 1.0 / (sigma * sigma) }
        val weightedSpeedSum = sources.sumOf { (speed, sigma) -> speed / (sigma * sigma) }
        if (!weightSum.isFinite() || weightSum <= 0.0 || !weightedSpeedSum.isFinite() || weightedSpeedSum < 0.0) return 0.0
        val speed = weightedSpeedSum / weightSum
        return if (speed.isFinite()) speed.coerceIn(0.0, MAX_SPEED_MPS) else 0.0
    }

    /** Validate supplied metadata before the uncertainty floor can make it appear usable. */
    private fun validNetworkEstimate(estimate: SpeedEstimate) = validNonNegative(estimate.speedMps) && validNonNegative(estimate.sigmaMps) && validNonNegative(estimate.spanS)

    private fun validNonNegative(value: Double) = value.isFinite() && value >= 0.0
}

/**
 * Speed from network/cell fixes while GPS is unusable.
 *
 * Each network fix is projected onto the route, giving "at time t we were at position s".
 * Fitting a straight line `s = a + b·t` through these points gives the speed as the slope `b`.
 * Network fixes are inaccurate (±20…500 m), so:
 *  - the line is fitted with weights 1 / accuracy² (precise fixes count more),
 *  - points farther than max(250 m, 3 × accuracy) from the line are dropped and the line refitted.
 */
class NetSpeedEstimator {
    private class Sample(val elapsedMs: Long, val s: Double, val accuracyM: Double)

    /** `s = intercept + slope × seconds since t0`; [slopeSigma] = uncertainty of the slope. */
    private class Line(val intercept: Double, val slope: Double, val slopeSigma: Double, val t0: Long) {
        fun sAt(elapsedMs: Long) = intercept + slope * (elapsedMs - t0) / 1000.0

        /** A finite fit with positive uncertainty is required before it can support movement. */
        fun isValid() = intercept.isFinite() && slope.isFinite() && slopeSigma.isFinite() && slopeSigma > 0.0
    }

    private val samples = ArrayList<Sample>()

    fun clear() = samples.clear()

    /** Add valid route evidence; rejection leaves the timestamp available for a corrected sample. */
    fun add(s: Double, accM: Double, elapsedMs: Long) {
        if (!s.isFinite() || !accM.isFinite() || accM < 0.0) return
        if (samples.isNotEmpty() && elapsedMs <= samples.last().elapsedMs) return // keep time order
        samples += Sample(elapsedMs, s, accM)
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
    }

    /** Try short windows first (reacts faster to speed changes), then fall back to 90 s. */
    fun estimate(nowMs: Long): SpeedEstimate? = estimate(nowMs, windowMs = 30_000, minSpanS = 15.0)
        ?: estimate(nowMs, windowMs = 40_000, minSpanS = 20.0)
        ?: strictEstimate(nowMs)

    /** The most reliable (slowest-reacting) estimate: 90 s of data covering at least 30 s. */
    fun strictEstimate(nowMs: Long): SpeedEstimate? = estimate(nowMs, windowMs = 90_000, minSpanS = 30.0)

    private fun estimate(nowMs: Long, windowMs: Long, minSpanS: Double): SpeedEstimate? {
        // Future observations and overflowing ages cannot supply evidence for a historical query.
        var window = samples.filter { it.elapsedMs <= nowMs && nowMs - it.elapsedMs in 0..windowMs }
        if (window.size < MIN_POINTS) return null
        var line = fit(window) ?: return null
        val inliers = window.filter { abs(it.s - line.sAt(it.elapsedMs)) <= max(250.0, weightAccuracy(it) * 3) }
        if (inliers.size < MIN_POINTS) return null
        if (inliers.size < window.size) {
            line = fit(inliers) ?: return null
            window = inliers
        }
        val spanS = (window.last().elapsedMs - window.first().elapsedMs) / 1000.0
        if (spanS < minSpanS) return null
        val sigma = line.slopeSigma * 1.5 // be a bit pessimistic: network errors are correlated
        if (!sigma.isFinite() || sigma <= 0.0 || sigma > MAX_SIGMA) return null // too uncertain to be useful
        return SpeedEstimate(max(line.slope, 0.0), sigma, window.size, spanS)
    }

    /** Accuracy used for weighting; never better than 20 m (even "5 m" network fixes are not that good). */
    private fun weightAccuracy(sample: Sample) = max(sample.accuracyM, 20.0)

    /** Weighted least-squares straight line through the points (null if they are all at one time). */
    private fun fit(points: List<Sample>): Line? {
        if (points.size < 2) return null
        val t0 = points.first().elapsedMs
        fun seconds(p: Sample) = (p.elapsedMs - t0) / 1000.0
        fun weight(p: Sample) = 1.0 / (weightAccuracy(p) * weightAccuracy(p))

        val weightSum = points.sumOf { weight(it) }
        val meanT = points.sumOf { weight(it) * seconds(it) } / weightSum
        val meanS = points.sumOf { weight(it) * it.s } / weightSum
        // Weighted variance of t and covariance of t and s (without the 1/n).
        val varianceT = points.sumOf { weight(it) * (seconds(it) - meanT) * (seconds(it) - meanT) }
        val covarianceTS = points.sumOf { weight(it) * (seconds(it) - meanT) * (it.s - meanS) }
        if (varianceT <= 1e-9) return null
        val slope = covarianceTS / varianceT
        return Line(meanS - meanT * slope, slope, sqrt(1.0 / varianceT), t0).takeIf { it.isValid() }
    }

    private companion object {
        const val MAX_SAMPLES = 60
        const val MIN_POINTS = 4
        const val MAX_SIGMA = 4.0
    }
}

/** Where [SpeedProfile] keeps its learned values (SharedPreferences on the phone). */
interface SpeedProfileStore {
    fun load(): SpeedProfile.State
    fun save(state: SpeedProfile.State)

    /** Keeps nothing on disk — for tests and the replay tool. */
    object InMemory : SpeedProfileStore {
        private var state = SpeedProfile.State()
        override fun load() = state
        override fun save(state: SpeedProfile.State) {
            this.state = state
        }
    }
}

/**
 * Learns how fast this driver goes compared with the speed limit, separately in the city
 * (limit below 70 km/h) and on highways. Used to turn a speed limit into an expected speed.
 */
class SpeedProfile(private val store: SpeedProfileStore = SpeedProfileStore.InMemory) {
    /** Ratios are "actual speed / limit" (0.8 = 20 % below the limit); N = samples so far. */
    data class State(val cityRatio: Double = 0.8, val highwayRatio: Double = 0.8, val cityN: Int = 0, val highwayN: Int = 0)

    var state: State = store.load()
        private set

    fun ratioFor(limitKmh: Int): Double = if (limitKmh < CITY_LIMIT_KMH) state.cityRatio else state.highwayRatio

    /** Learn from one GPS speed measured on a road with limit [limitKmh]. */
    fun learn(gpsSpeedMps: Double, limitKmh: Int) {
        if (limitKmh <= 0 || gpsSpeedMps < 4.0) return // standing / crawling tells nothing
        val ratio = (gpsSpeedMps * 3.6 / limitKmh).coerceIn(0.2, 2.5)

        // Running average; after 60 samples every new one moves the value by 1/61 (slow drift).
        fun blend(old: Double, n: Int) = old + (ratio - old) / (min(n, 60) + 1)
        val old = state
        state = if (limitKmh < CITY_LIMIT_KMH) {
            old.copy(cityRatio = blend(old.cityRatio, old.cityN), cityN = old.cityN + 1)
        } else {
            old.copy(highwayRatio = blend(old.highwayRatio, old.highwayN), highwayN = old.highwayN + 1)
        }
        if ((state.cityN + state.highwayN) % SAVE_EVERY == 0) store.save(state)
    }

    private companion object {
        const val CITY_LIMIT_KMH = 70
        const val SAVE_EVERY = 20
    }
}

/** The expected cruising speed at a place on the route, from map data alone. */
object RouteSpeedPrior {
    /** Ukrainian international / national / regional / territorial road numbers (М-06, Н-01, Р-02, Т-10…). */
    private val MAJOR_ROAD = Regex("(^|[\\s(,/])[МНРТEЕMHPT]-?\\d{1,2}")

    /**
     * Expected speed at [s], in m/s, from the best information available:
     *  1. the speed limit × this driver's usual ratio,
     *  2. the router's modelled speed for this segment,
     *  3. the average speed of the current step, clamped to sane values per road class.
     */
    fun at(route: Route, s: Double, profile: SpeedProfile): Double? {
        val segment = route.segmentAt(s)
        route.maxspeedAtSegment(segment)?.let { limit -> return limit / 3.6 * profile.ratioFor(limit) }
        route.modelledSpeedAtSegment(segment)?.let { return it }

        val currentStep = route.steps.indices.lastOrNull { route.stepS(it) <= s }
        val step = currentStep?.let { route.steps[it] } ?: route.steps.firstOrNull() ?: return null
        val major = MAJOR_ROAD.containsMatchIn(step.name) || step.distanceM >= 3000
        val kmh = if (step.durationS <= 0 || step.distanceM < 200) {
            if (major) 80.0 else 40.0 // too short to compute an average: typical values
        } else {
            val average = step.distanceM / step.durationS * 3.6
            if (major) average.coerceIn(50.0, 110.0) else average.coerceIn(15.0, 70.0)
        }
        return kmh / 3.6
    }
}

/** Slows the dead-reckoned speed down where cars really slow down. */
object SpeedPlan {
    private const val SIGNAL_CAP_MPS = 3.0
    private const val CALMING_CAP_MPS = 5.0

    /**
     * Speed cap at [s], or null for none: 3 m/s approaching a traffic signal (only without a
     * network speed, which would know better), 5 m/s over speed bumps.
     */
    fun cap(hazards: List<Hazard>, s: Double, noNetworkSpeed: Boolean): Double? {
        val caps = hazards.mapNotNull { hazard ->
            val ahead = hazard.s - s // metres until the hazard (negative = just passed it)
            when (hazard.kind) {
                HazardKind.TRAFFIC_SIGNAL -> SIGNAL_CAP_MPS.takeIf { noNetworkSpeed && ahead in -5.0..45.0 }
                HazardKind.TRAFFIC_CALMING -> CALMING_CAP_MPS.takeIf { ahead in -6.0..20.0 }
            }
        }
        return caps.minOrNull()
    }
}
