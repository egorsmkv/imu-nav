package org.imunav.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataUsageTest {
    @Test fun totalAndSplitDescribeBothDirections() {
        val usage = DataUsage.fromCounters(750_000_000, 250_000_000)
        assertEquals(1_000_000_000L, usage.totalBytes)
        assertEquals(0.75f, usage.receivedFraction)
        assertEquals(0f, DataUsage.fromCounters(0, 42).receivedFraction)
        assertEquals(1f, DataUsage.fromCounters(42, 0).receivedFraction)
    }

    @Test fun emptyUnavailableAndOverflowingTotalsHaveNoSplit() {
        val empty = DataUsage.fromCounters(0, 0)
        assertEquals(0L, empty.totalBytes)
        assertNull(empty.receivedFraction)
        for (usage in listOf(DataUsage.fromCounters(-1, 42), DataUsage.fromCounters(42, -1), DataUsage.fromCounters(Long.MAX_VALUE, 1))) {
            assertNull(usage.totalBytes)
            assertNull(usage.receivedFraction)
        }
        assertEquals(Long.MAX_VALUE, DataUsage.fromCounters(Long.MAX_VALUE, 0).totalBytes)
    }

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
