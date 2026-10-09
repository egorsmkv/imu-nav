package org.imunav.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataUsageTest {
    @Test fun keepsZeroAndLargeCounters() {
        val usage = DataUsage.fromCounters(0, 5_000_000_000L)
        assertEquals(0L, usage.receivedBytes)
        assertEquals(5_000_000_000L, usage.sentBytes)
    }

    @Test fun unsupportedDirectionsStayIndependent() {
        val receivedUnavailable = DataUsage.fromCounters(-1, 42)
        assertNull(receivedUnavailable.receivedBytes)
        assertEquals(42L, receivedUnavailable.sentBytes)
        val sentUnavailable = DataUsage.fromCounters(42, -1)
        assertEquals(42L, sentUnavailable.receivedBytes)
        assertNull(sentUnavailable.sentBytes)
        assertEquals(DataUsage(null, null), DataUsage.fromCounters(-1, -1))
    }
}
