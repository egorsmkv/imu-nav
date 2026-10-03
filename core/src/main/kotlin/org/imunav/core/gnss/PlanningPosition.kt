package org.imunav.core.gnss

/** Fresh automatic origins for planning; this never changes the active navigation engine's position. */
object PlanningPosition {
    const val GPS_MAX_AGE_MS = 5_000L
    const val COARSE_MAX_AGE_MS = 30_000L

    /** Keep the complete fix so position, source and accuracy always refer to the same observation. */
    fun select(nowMs: Long, goodGps: RawFix?, coarse: RawFix?): RawFix? = goodGps?.takeIf { it.source == FixSource.GPS && nowMs - it.elapsedMs in 0..GPS_MAX_AGE_MS }
        ?: coarse?.takeIf { (it.source == FixSource.NET || it.source == FixSource.CELL) && nowMs - it.elapsedMs in 0..COARSE_MAX_AGE_MS }
}
