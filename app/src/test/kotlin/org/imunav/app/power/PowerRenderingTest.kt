package org.imunav.app.power

import org.junit.Assert.assertEquals
import org.junit.Test

/** Device rendering limits must never silently lower navigation input rates or accuracy. */
class PowerRenderingTest {
    @Test
    fun everySensorProfileSurvivesConstrainedRenderingUnchanged() {
        for (base in listOf(PowerProfile.PERFORMANCE, PowerProfile.BALANCED, PowerProfile.SAVER)) {
            val limited = base.withRenderingBudget(constrained = true, explicitPerformance = false)
            assertEquals(15, limited.mapMaxFps)
            assertEquals(false, limited.animateCamera)
            assertEquals(0, limited.mapPrefetchZoomDelta)
            assertEquals(base, limited.copy(mapMaxFps = base.mapMaxFps, animateCamera = base.animateCamera, mapPrefetchZoomDelta = base.mapPrefetchZoomDelta))
        }
    }

    @Test
    fun explicitPerformanceAndUnconstrainedDevicesKeepOriginalRendering() {
        assertEquals(PowerProfile.PERFORMANCE, PowerProfile.PERFORMANCE.withRenderingBudget(true, true))
        assertEquals(PowerProfile.BALANCED, PowerProfile.BALANCED.withRenderingBudget(false, false))
        assertEquals(PowerProfile.SAVER, PowerProfile.SAVER.withRenderingBudget(false, false))
    }
}
