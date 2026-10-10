package org.imunav.core

import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.record.TripEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exported positions must never return through GPS or vendor relays as independent evidence. */
class MockLocationFeedbackTest {
    @Test
    fun mockNetworkFusedAndCellFixesAreRecordedButCannotReplaceEvidence() {
        val hub = PositioningHub()
        val recorded = mutableListOf<TripEvent>()
        hub.recorder = { recorded += it }
        val realNetwork = fix(FixSource.NET, mock = false)
        hub.onFix(realNetwork)
        for (source in listOf(FixSource.NET, FixSource.FUSED, FixSource.CELL)) {
            val exported = fix(source, mock = true)
            assertNull(hub.onFix(exported))
            assertEquals(TripEvent.Fix(exported), recorded.last())
        }
        assertEquals(realNetwork, hub.lastNet)
        assertNull(hub.lastFused)
        assertNull(hub.lastCell)
    }

    @Test
    fun mockGpsNeverBecomesGoodOrUsable() {
        val hub = PositioningHub(wallClock = { 1000L })
        assertEquals(TrustLevel.BAD, hub.onFix(fix(FixSource.GPS, mock = true))?.level)
        assertNull(hub.lastGood)
        assertNull(hub.lastUsable)
    }

    private fun fix(source: FixSource, mock: Boolean) = RawFix(source, 1000L, 1000L, 50.45, 30.52, accuracyM = 10f, isMock = mock)
}
