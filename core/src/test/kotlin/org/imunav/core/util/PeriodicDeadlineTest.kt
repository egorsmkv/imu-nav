package org.imunav.core.util

import org.junit.Test
import kotlin.test.assertEquals

class PeriodicDeadlineTest {
    @Test
    fun workDoesNotAccumulateDriftAcrossLongTrips() {
        val cadence = PeriodicDeadline(0, 500)
        repeat(50_000) { tick -> assertEquals(450, cadence.delayAfterWork(tick * 500L + 50)) }
    }

    @Test
    fun overrunSkipsMissedDeadlinesWithoutCatchUp() {
        val cadence = PeriodicDeadline(1000, 500)
        assertEquals(400, cadence.delayAfterWork(1100))
        assertEquals(300, cadence.delayAfterWork(2700))
        assertEquals(450, cadence.delayAfterWork(3050))
        assertEquals(500, cadence.delayAfterWork(4000))
    }
}
