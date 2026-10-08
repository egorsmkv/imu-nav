package org.imunav.core.nav

import org.imunav.core.Tuning
import org.imunav.core.gnss.RawFix
import org.imunav.core.route.Projection
import org.imunav.core.route.RouteCursor
import kotlin.math.max

/** Counts independent network fixes that place the vehicle far from the planned route. */
internal class NetworkDeviationDetector {
    private var fastCount = 0
    private var fastKey: String? = null
    private var count = 0

    /** Return a candidate only after the configured number of disagreeing fixes. */
    fun check(car: RouteCursor, projection: Projection, accuracyM: Double, fix: RawFix, gpsRecent: Boolean, rerouting: Boolean, config: Tuning): Trigger? {
        if (gpsRecent || rerouting || !config.blindDeviationEnabled) {
            fastCount = 0
            count = 0
            fastKey = null
            return null
        }
        // This check precedes NetworkTracker.gate in the engine, so validate its own evidence.
        if (fix.elapsedMs < 0 || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0) return null
        if (!accuracyM.isFinite() || accuracyM < 0.0 || !projection.s.isFinite() || !projection.offsetM.isFinite() || projection.offsetM < 0.0) return null
        val remaining = car.route.length - car.s
        val offset = projection.offsetM
        if (remaining >= 1000.0 && accuracyM <= 300.0) {
            val key = "${fix.lat},${fix.lon}"
            if (key != fastKey) {
                fastKey = key
                if (offset >= max(100.0, 30.0 + 2.5 * accuracyM)) {
                    fastCount++
                } else if (offset <= accuracyM + 30.0) {
                    fastCount = 0
                }
                if (fastCount >= 3) {
                    fastCount = 0
                    count = 0
                    return Trigger(offset, accuracyM, fast = true)
                }
            }
        }
        val threshold = if (remaining < 1000.0) 400.0 else 250.0
        if (accuracyM > 300.0 || offset < accuracyM + 150.0 || offset < threshold) {
            count = 0
            return null
        }
        count++
        if (count < 3) return null
        count = 0
        return Trigger(offset, accuracyM, fast = false)
    }

    /** Evidence needed to produce the existing deviation log and voice prompt. */
    data class Trigger(val offsetM: Double, val accuracyM: Double, val fast: Boolean)
}
