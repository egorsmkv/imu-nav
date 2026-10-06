package org.imunav.core.diagnostics

/** Full process memory scans are costly; cheap counters retain the finer time resolution. */
class ProfileSampling {
    enum class Kind { CHEAP, DETAILED }
    private var lastSample: Long? = null
    private var lastDetailed: Long? = null

    /** A finish sample always includes PSS, even for captures shorter than one interval. */
    fun next(elapsedMs: Long, finishing: Boolean = false): Kind? {
        if (!finishing && lastSample?.let { elapsedMs - it < HEAP_INTERVAL_MS } == true) return null
        lastSample = elapsedMs
        return if (finishing || lastDetailed?.let { elapsedMs - it >= DETAILED_INTERVAL_MS } != false) {
            lastDetailed = elapsedMs
            Kind.DETAILED
        } else {
            Kind.CHEAP
        }
    }

    companion object {
        const val HEAP_INTERVAL_MS = 5_000L
        const val DETAILED_INTERVAL_MS = 15_000L
    }
}
