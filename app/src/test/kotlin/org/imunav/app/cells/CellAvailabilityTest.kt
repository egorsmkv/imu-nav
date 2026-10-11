package org.imunav.app.cells

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** A usable secondary SIM must survive absent, locked or unknown primary-SIM states. */
class CellAvailabilityTest {
    @Test
    fun readySimWorksInEitherSlot() {
        for (other in listOf(TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_UNKNOWN)) {
            assertEquals(CellAvailability.AVAILABLE, cellSimAvailability(listOf(other, TelephonyManager.SIM_STATE_READY)))
            assertEquals(CellAvailability.AVAILABLE, cellSimAvailability(listOf(TelephonyManager.SIM_STATE_READY, other)))
        }
    }

    @Test
    fun noSimRequiresConfirmedAbsenceAcrossSlots() {
        assertEquals(CellAvailability.NO_SIM, cellSimAvailability(listOf(TelephonyManager.SIM_STATE_ABSENT)))
        assertEquals(CellAvailability.NO_SIM, cellSimAvailability(List(2) { TelephonyManager.SIM_STATE_ABSENT }))
        assertEquals(CellAvailability.SIM_UNAVAILABLE, cellSimAvailability(emptyList()))
        assertEquals(CellAvailability.SIM_UNAVAILABLE, cellSimAvailability(listOf(TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_UNKNOWN)))
    }

    @Test
    fun lockedOrDisabledSimIsUnavailable() {
        for (state in listOf(TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED, TelephonyManager.SIM_STATE_NOT_READY)) {
            assertEquals(CellAvailability.SIM_UNAVAILABLE, cellSimAvailability(listOf(state)))
        }
    }
}
