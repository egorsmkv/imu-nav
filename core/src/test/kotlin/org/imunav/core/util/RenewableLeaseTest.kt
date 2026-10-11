package org.imunav.core.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenewableLeaseTest {
    @Test
    fun screenOffTripKeepsRenewingBeyondSixHours() {
        val hour = 60 * 60 * 1000L
        val lease = RenewableLease(6 * hour, 3 * hour)
        var expires = 0L
        var acquisitions = 0
        for (now in 0..12 * hour step 500) {
            lease.renewIfDue(now) { timeout ->
                expires = now + timeout
                acquisitions++
            }
            assertTrue(expires > now)
        }
        assertEquals(5, acquisitions)
        assertEquals(18 * hour, expires)
    }
}
