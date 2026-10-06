package org.imunav.core.power

/** Rendering limits are independent of sensor accuracy and navigation update rates. */
data class MapRenderingBudget(val maximumFps: Int, val animateCamera: Boolean, val prefetchZoomDelta: Int) {
    companion object {
        const val DEFAULT_PREFETCH_ZOOM_DELTA = 4
        private const val CONSTRAINED_FPS = 15
        private const val CONSTRAINED_RAM_BYTES = 4L * 1024 * 1024 * 1024

        /** Unknown RAM does not classify a device unless Android explicitly reports low RAM. */
        fun isConstrained(lowRam: Boolean, totalRamBytes: Long): Boolean = lowRam || totalRamBytes in 1..CONSTRAINED_RAM_BYTES

        /** Explicit performance mode overrides the device limit, including when Auto is charging. */
        fun resolve(constrained: Boolean, explicitPerformance: Boolean, maximumFps: Int, animateCamera: Boolean): MapRenderingBudget = if (constrained && !explicitPerformance) {
            MapRenderingBudget(minOf(maximumFps, CONSTRAINED_FPS), false, 0)
        } else {
            MapRenderingBudget(maximumFps, animateCamera, DEFAULT_PREFETCH_ZOOM_DELTA)
        }
    }
}
