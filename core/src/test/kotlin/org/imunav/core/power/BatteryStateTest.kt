package org.imunav.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise discharge, noisy levels, charging and missing readings independently of Android. */
class BatteryStateTest {
    @Test
    fun dischargeEntersSaverAtTwentyAndRecoversOnlyAtTwentyFive() {
        var battery = BatteryState().update(21, false, false)
        assertFalse(battery.requiresSaver)
        battery = battery.update(20, false, false)
        assertTrue(battery.requiresSaver)
        for (percent in listOf(21, 19, 24, 20)) {
            battery = battery.update(percent, false, false)
            assertTrue(battery.requiresSaver)
        }
        assertFalse(battery.update(25, false, false).requiresSaver)
    }

    @Test
    fun chargingTemporarilyBypassesLowBatteryWithoutLosingTheLatch() {
        val low = BatteryState().update(10, false, false)
        val charging = low.update(22, true, false)
        assertFalse(charging.requiresSaver)
        assertTrue(charging.update(22, false, false).requiresSaver)
        assertFalse(charging.update(25, true, false).update(24, false, false).requiresSaver)
    }

    @Test
    fun missingOrInvalidReadingsNeverInventARecoveryOrALowBattery() {
        val low = BatteryState().update(0, false, false)
        for (percent in listOf(null, -1, 101)) {
            assertFalse(BatteryState().update(percent, false, false).requiresSaver)
            assertTrue(low.update(percent, false, false).requiresSaver)
            assertEquals(null, low.update(percent, false, false).percent)
        }
    }
}
