package org.imunav.core.gnss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlanningPositionTest {
    private val now = 100_000L
    private val gps = RawFix(FixSource.GPS, 0, now - 5_000, 50.0, 30.0, accuracyM = 4f)
    private val cell = RawFix(FixSource.CELL, 0, now - 30_000, 51.0, 31.0, accuracyM = 500f)

    @Test
    fun freshGpsWinsAndKeepsItsSourceAndAccuracy() {
        assertSame(gps, PlanningPosition.select(now, gps, cell))
        val freshCell = cell.copy(elapsedMs = now)
        assertSame(freshCell, PlanningPosition.select(now + 1, gps, freshCell))
    }

    @Test
    fun coarseFixExpiresAfterThirtySeconds() {
        assertSame(cell, PlanningPosition.select(now, null, cell))
        assertNull(PlanningPosition.select(now + 1, gps, cell))
    }

    @Test
    fun idleGpsStatusExpiresAndCannotTreatFutureFixAsCurrent() {
        val wall = 1_700_000_000_000L
        val hub = PositioningHub(wallClock = { wall })
        assertEquals(TrustLevel.GOOD, hub.onFix(gps.copy(timeMs = wall, elapsedMs = now))?.level)
        assertEquals(GpsState.OK, hub.updateGpsState(now + 5_000))
        assertEquals(GpsState.DEGRADED, hub.updateGpsState(now + 5_001))
        assertEquals(GpsState.LOST, hub.updateGpsState(now + 30_001))
        assertEquals(GpsState.LOST, hub.updateGpsState(now - 1))
    }

    @Test
    fun futureTimesAndFusedPositionsCannotStartNavigation() {
        val future = gps.copy(elapsedMs = now + 1)
        assertSame(cell, PlanningPosition.select(now, future, cell))
        assertNull(PlanningPosition.select(now, future, cell.copy(elapsedMs = now + 1)))
        assertNull(PlanningPosition.select(now, null, cell.copy(source = FixSource.FUSED)))
    }
}
