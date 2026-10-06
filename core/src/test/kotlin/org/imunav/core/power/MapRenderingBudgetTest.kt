package org.imunav.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRenderingBudgetTest {
    @Test fun classificationHandlesBoundaryUnknownAndAndroidLowRamFlag() {
        val limit = 4L * 1024 * 1024 * 1024
        assertTrue(MapRenderingBudget.isConstrained(false, limit))
        assertFalse(MapRenderingBudget.isConstrained(false, limit + 1))
        assertFalse(MapRenderingBudget.isConstrained(false, 0))
        assertFalse(MapRenderingBudget.isConstrained(false, -1))
        assertTrue(MapRenderingBudget.isConstrained(true, 0))
        assertTrue(MapRenderingBudget.isConstrained(true, limit * 2))
    }

    @Test fun autoChargingStillLimitsRenderingButExplicitPerformanceOverrides() {
        assertEquals(MapRenderingBudget(15, false, 0), MapRenderingBudget.resolve(true, false, 60, true))
        assertEquals(MapRenderingBudget(60, true, 4), MapRenderingBudget.resolve(true, true, 60, true))
        assertEquals(MapRenderingBudget(30, true, 4), MapRenderingBudget.resolve(false, false, 30, true))
        assertEquals(MapRenderingBudget(15, false, 0), MapRenderingBudget.resolve(true, false, 15, false))
        assertEquals(MapRenderingBudget(10, false, 0), MapRenderingBudget.resolve(true, false, 10, false))
    }
}
