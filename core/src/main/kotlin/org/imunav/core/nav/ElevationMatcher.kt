package org.imunav.core.nav

import org.imunav.core.route.Route
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds where along the route the car is by comparing **how the road climbs and falls** with what
 * the phone's barometer measured on the way ("terrain matching").
 *
 * The barometer only knows height *changes* well: absolute pressure also moves with the weather,
 * but over a few minutes that is far below a metre. So we keep the recent history as
 * (distance travelled, barometric height) pairs, remove the mean height, and slide that little
 * height trace along the route's elevation profile. Where it fits best is where we are.
 *
 * Distances in the history come from the engine's odometer (speed × time), not from the marker's
 * position, so turn snaps and other corrections do not distort the trace. The odometer's scale may
 * be off by the speed estimate's error, so a few scale factors are tried as well.
 *
 * A match is only reported when it is trustworthy: enough relief in the window (flat roads say
 * nothing), a close fit, and no other place along the route that fits almost as well.
 */
class ElevationMatcher {
    /** One history point: odometer reading and smoothed barometric height. */
    private class Sample(val odometerM: Double, val heightM: Double)

    private val history = ArrayDeque<Sample>()
    private var smoothedHeight: Double? = null
    private var lastPressureMs = -1L

    /** Latest smoothed barometric height (m, standard atmosphere), null before the first reading. */
    val heightM: Double? get() = smoothedHeight

    /** Forget everything (new route or navigation stopped). */
    fun reset() {
        history.clear()
        smoothedHeight = null
        lastPressureMs = -1L
    }

    /** A barometer reading. Heights are low-pass filtered (~2 s) to remove sensor noise and door slams. */
    fun onPressure(hPa: Double, elapsedMs: Long) {
        if (hPa !in MIN_HPA..MAX_HPA) return
        val height = heightFromPressure(hPa)
        val previous = smoothedHeight
        val dtS = if (lastPressureMs < 0) 0.0 else ((elapsedMs - lastPressureMs) / 1000.0).coerceIn(0.0, 5.0)
        lastPressureMs = elapsedMs
        smoothedHeight = if (previous == null) height else previous + (height - previous) * (dtS / (dtS + SMOOTHING_S))
    }

    /** Called every engine tick with the odometer; stores a history point every [SAMPLE_EVERY_M] of travel. */
    fun onTravel(odometerM: Double) {
        val height = smoothedHeight ?: return
        val last = history.lastOrNull()
        if (last != null && odometerM - last.odometerM < SAMPLE_EVERY_M) return
        history.addLast(Sample(odometerM, height))
        while (history.size > 2 && odometerM - history.first().odometerM > WINDOW_M) history.removeFirst()
    }

    /** Distance covered by the history so far, metres. */
    val windowM: Double get() = if (history.size < 2) 0.0 else history.last().odometerM - history.first().odometerM

    /**
     * Where does the height trace fit on [route]? Searches the car's current position within
     * [currentS] ± [searchM]. [scales] are odometer scale factors to try (1.0 = odometer is exact).
     * Returns null when the answer is not trustworthy.
     */
    fun match(route: Route, currentS: Double, searchM: Double, scales: List<Double> = DEFAULT_SCALES): ElevationMatch? {
        if (!route.hasElevation || history.size < MIN_SAMPLES || windowM < MIN_WINDOW_M) return null
        val newest = history.last().odometerM
        // Distance of every sample behind the newest one, and the measured heights around their mean.
        val behind = DoubleArray(history.size) { newest - history[it].odometerM }
        val measuredMean = history.sumOf { it.heightM } / history.size
        val measured = DoubleArray(history.size) { history[it].heightM - measuredMean }

        val low = (currentS - searchM).coerceAtLeast(0.0)
        val high = (currentS + searchM).coerceAtMost(route.length)
        val candidates = ArrayList<Candidate>()
        var candidateS = low
        while (candidateS <= high) {
            for (scale in scales) fit(route, candidateS, scale, behind, measured)?.let { candidates += it }
            candidateS += STEP_M
        }
        val best = candidates.minByOrNull { it.rmsM } ?: return null
        // Relief of the road under the best fit: on a flat road every position fits equally well.
        if (best.reliefM < MIN_RELIEF_M || best.rmsM > MAX_RMS_M) return null
        // The best place must clearly beat every other place (not just its own neighbours).
        val rival = candidates.filter { abs(it.s - best.s) > RIVAL_SEPARATION_M }.minByOrNull { it.rmsM }
        val ratio = rival?.let { it.rmsM / best.rmsM.coerceAtLeast(MIN_RMS_FOR_RATIO) } ?: Double.MAX_VALUE
        if (ratio < MIN_RIVAL_RATIO) return null
        return ElevationMatch(best.s, best.scale, best.rmsM, best.reliefM, ratio)
    }

    private class Candidate(val s: Double, val scale: Double, val rmsM: Double, val reliefM: Double)

    /** RMS misfit when the newest sample is at route position [newestS] and the odometer is scaled by [scale]. */
    private fun fit(route: Route, newestS: Double, scale: Double, behind: DoubleArray, measured: DoubleArray): Candidate? {
        val oldestS = newestS - behind[0] * scale
        if (oldestS < 0.0) return null // the trace would start before the route does
        val profile = DoubleArray(behind.size)
        for (i in behind.indices) profile[i] = route.elevationAt(newestS - behind[i] * scale) ?: return null
        val profileMean = profile.average()
        var squares = 0.0
        var reliefSquares = 0.0
        for (i in profile.indices) {
            val expected = profile[i] - profileMean
            val error = measured[i] - expected
            squares += error * error
            reliefSquares += expected * expected
        }
        return Candidate(newestS, scale, sqrt(squares / profile.size), sqrt(reliefSquares / profile.size))
    }

    companion object {
        /** Height from air pressure in the standard atmosphere (the same formula Android uses). */
        fun heightFromPressure(hPa: Double): Double = 44_330.0 * (1.0 - (hPa / 1013.25).pow(1.0 / 5.255))

        private const val MIN_HPA = 300.0
        private const val MAX_HPA = 1_100.0
        private const val SMOOTHING_S = 2.0

        /** History resolution and length. */
        private const val SAMPLE_EVERY_M = 10.0
        private const val WINDOW_M = 1_500.0
        private const val MIN_WINDOW_M = 400.0
        private const val MIN_SAMPLES = 30

        /** Candidate positions every 5 m. */
        private const val STEP_M = 5.0

        /** The road must rise and fall by at least this much (RMS) inside the window, metres. */
        const val MIN_RELIEF_M = 4.0

        /** A good fit follows the profile within this RMS, metres (DEM + barometer noise). */
        const val MAX_RMS_M = 2.5

        /** Another candidate this far away must fit at least [MIN_RIVAL_RATIO] times worse. */
        private const val RIVAL_SEPARATION_M = 60.0
        const val MIN_RIVAL_RATIO = 1.8
        private const val MIN_RMS_FOR_RATIO = 0.5

        /**
         * Odometer from estimated speed: after minutes without GPS the estimate (speed limits × habits)
         * can be off by a third, so scales from 0.75 to 1.35 are tried.
         */
        val DEFAULT_SCALES = (0..12).map { 0.75 + it * 0.05 }

        /** Odometer from the car's own speed (OBD-II): nearly exact. */
        val VEHICLE_SPEED_SCALES = listOf(0.97, 1.0, 1.03)
    }
}

/**
 * A trustworthy terrain match: the car is at [s]; the odometer scale that fitted best, the RMS
 * misfit and the road's relief in the window (metres), and how much worse the best rival fitted.
 */
data class ElevationMatch(val s: Double, val scale: Double, val rmsM: Double, val reliefM: Double, val rivalRatio: Double)
