package org.imunav.core.power

/** Battery readings and a low-charge latch that avoids switching modes around a single threshold. */
data class BatteryState(val percent: Int? = null, val plugged: Boolean = false, val systemSaver: Boolean = false, val low: Boolean = false) {
    val requiresSaver: Boolean get() = low && !plugged

    /** Keep low-charge protection through noisy or missing readings; charging temporarily bypasses it. */
    fun update(percent: Int?, plugged: Boolean, systemSaver: Boolean): BatteryState {
        val validPercent = percent?.takeIf { it in 0..100 }
        val lowCharge = when {
            validPercent == null -> low
            validPercent <= LOW_PERCENT -> true
            validPercent >= RECOVERED_PERCENT -> false
            else -> low
        }
        return BatteryState(validPercent, plugged, systemSaver, lowCharge)
    }

    companion object {
        const val LOW_PERCENT = 20
        const val RECOVERED_PERCENT = 25
    }
}
