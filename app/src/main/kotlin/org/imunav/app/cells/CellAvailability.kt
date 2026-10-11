package org.imunav.app.cells

import android.telephony.TelephonyManager

/** Why cellular positioning cannot currently produce a fix, independently of GPS or dead reckoning. */
enum class CellAvailability {
    CHECKING,
    AVAILABLE,
    NO_CELLULAR,
    NO_SIM,
    SIM_UNAVAILABLE,
    NO_CELLS,
    SCAN_FAILED,
    NO_KNOWN_TOWERS,
    DISABLED,
}

/** Evaluate all SIMs together: an absent or locked secondary SIM must not hide a usable one. */
internal fun cellSimAvailability(simStates: List<Int>): CellAvailability = when {
    simStates.any { it == TelephonyManager.SIM_STATE_READY } -> CellAvailability.AVAILABLE
    simStates.isNotEmpty() && simStates.all { it == TelephonyManager.SIM_STATE_ABSENT } -> CellAvailability.NO_SIM
    else -> CellAvailability.SIM_UNAVAILABLE
}
