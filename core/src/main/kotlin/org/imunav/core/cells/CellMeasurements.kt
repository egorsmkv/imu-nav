package org.imunav.core.cells

import org.imunav.core.Tuning
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix

/** A cell observation with its modem measurement time, in elapsed milliseconds since boot. */
data class CellMeasurement(val observation: CellObservation, val elapsedMs: Long)

/** Reject missing, future and stale measurement times before subtracting, avoiding overflow. */
fun isFreshCellMeasurement(elapsedMs: Long, nowMs: Long): Boolean = elapsedMs > 0 && elapsedMs <= nowMs && nowMs - elapsedMs <= Tuning.CELL_MAX_AGE_MS

/** Both the GPS label and each cell must still be fresh when learning runs, even if scans stopped. */
fun cellLearningKeys(measurements: List<CellMeasurement>, gpsElapsedMs: Long, nowMs: Long): List<CellKey> = if (isFreshCellMeasurement(gpsElapsedMs, nowMs)) {
    measurements.filter { isFreshCellMeasurement(it.elapsedMs, nowMs) }.map { it.observation.key }
} else {
    emptyList()
}

/**
 * Worker-confined modem cache. Callback arrival never refreshes a measurement's age, and a second
 * SIM cannot keep the first SIM's old cells alive. Fix times advance only with their contributors.
 */
class CellMeasurementTracker {
    private val perSim = mutableMapOf<Int, List<CellMeasurement>>()
    private var lastFixElapsedMs = 0L

    /** Merge recent measurements, choosing the newest copy of any cell reported by multiple SIMs. */
    fun update(subscriptionId: Int, measurements: List<CellMeasurement>, nowMs: Long): List<CellMeasurement> {
        perSim[subscriptionId] = measurements
        perSim.replaceAll { _, entries -> entries.filter { isFreshCellMeasurement(it.elapsedMs, nowMs) } }
        perSim.entries.removeAll { it.value.isEmpty() }
        return perSim.values.flatten()
            .sortedWith(compareByDescending<CellMeasurement> { it.elapsedMs }.thenByDescending { it.observation.serving })
            .distinctBy { it.observation.key }
    }

    /**
     * Date a fix by its oldest contributing measurement so newer neighbours cannot rejuvenate it.
     * Recheck age after database work; suppress repeated or out-of-order fixes before recording.
     */
    fun positionFix(fix: CellFix, measurements: List<CellMeasurement>, nowMs: Long, wallTimeMs: Long): RawFix? {
        val byKey = measurements.associateBy { it.observation.key }
        val times = fix.contributions.map { contribution ->
            val elapsedMs = byKey[contribution.observation.key]?.elapsedMs ?: return null
            if (!isFreshCellMeasurement(elapsedMs, nowMs)) return null
            elapsedMs
        }
        val elapsedMs = times.minOrNull() ?: return null
        if (elapsedMs <= lastFixElapsedMs) return null
        lastFixElapsedMs = elapsedMs
        return RawFix(
            source = FixSource.CELL,
            timeMs = wallTimeMs - (nowMs - elapsedMs),
            elapsedMs = elapsedMs,
            lat = fix.lat,
            lon = fix.lon,
            accuracyM = fix.accuracyM.toFloat(),
        )
    }
}
