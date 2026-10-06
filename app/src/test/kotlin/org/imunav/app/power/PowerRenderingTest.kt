package org.imunav.app.power

import org.imunav.core.power.BatteryState
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

    @Test
    fun manualCapOnlyLowersFpsAcrossAllProfilesAndDeviceBudgets() {
        for (base in listOf(PowerProfile.PERFORMANCE, PowerProfile.BALANCED, PowerProfile.SAVER)) {
            for (constrained in listOf(false, true)) {
                for (explicitPerformance in listOf(false, true)) {
                    val resolved = base.withRenderingBudget(constrained, explicitPerformance)
                    for (rate in MapFrameRate.entries) {
                        val capped = resolved.withFrameRateLimit(rate)
                        assertEquals(minOf(resolved.mapMaxFps, rate.maximumFps ?: resolved.mapMaxFps), capped.mapMaxFps)
                        assertEquals(resolved, capped.copy(mapMaxFps = resolved.mapMaxFps))
                    }
                }
            }
        }
    }

    @Test
    fun capSurvivesPowerModeChangesAndAutoRestoresPolicyLimit() {
        val selected = MapFrameRate.FPS_10
        assertEquals(10, PowerProfile.BALANCED.withFrameRateLimit(selected).mapMaxFps)
        assertEquals(10, PowerProfile.PERFORMANCE.withFrameRateLimit(selected).mapMaxFps)
        assertEquals(PowerProfile.PERFORMANCE, PowerProfile.PERFORMANCE.withFrameRateLimit(MapFrameRate.AUTO))
    }

    @Test
    fun lowBatteryOverridesEveryModeAndRestoresTheSelectedModeOnCharging() {
        val low = BatteryState().update(20, false, false)
        val charging = low.update(20, true, false)
        val recovered = low.update(25, false, false)
        for (mode in PowerMode.entries) {
            assertEquals(PowerProfile.SAVER, resolveProfile(mode, low))
            val expected = when (mode) {
                PowerMode.AUTO, PowerMode.PERFORMANCE -> PowerProfile.PERFORMANCE
                PowerMode.BALANCED -> PowerProfile.BALANCED
                PowerMode.SAVER -> PowerProfile.SAVER
            }
            assertEquals(expected, resolveProfile(mode, charging))
            assertEquals(if (mode == PowerMode.AUTO) PowerProfile.BALANCED else expected, resolveProfile(mode, recovered))
        }
    }

    @Test
    fun autoFollowsSystemSaverWhileManualModesKeepTheirExistingBehavior() {
        val systemSaver = BatteryState().update(70, false, true)
        assertEquals(PowerProfile.SAVER, resolveProfile(PowerMode.AUTO, systemSaver))
        assertEquals(PowerProfile.PERFORMANCE, resolveProfile(PowerMode.PERFORMANCE, systemSaver))
        assertEquals(PowerProfile.BALANCED, resolveProfile(PowerMode.AUTO, BatteryState()))
    }
}
