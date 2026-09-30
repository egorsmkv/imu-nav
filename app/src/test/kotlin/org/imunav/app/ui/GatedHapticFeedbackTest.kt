package org.imunav.app.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Retained UI listeners must observe disable/enable changes without being recreated. */
class GatedHapticFeedbackTest {
    @Test
    fun savedGateBlocksFeedbackImmediatelyWhenDisabledAndRestoresWhenEnabled() {
        val performed = mutableListOf<HapticFeedbackType>()
        val system = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                performed += hapticFeedbackType
            }
        }
        var enabled = false
        val gate = GatedHapticFeedback(system) { enabled }
        val type = HapticFeedbackType.LongPress
        gate.performHapticFeedback(type)
        assertEquals(0, performed.size)
        enabled = true
        gate.performHapticFeedback(type)
        assertEquals(listOf(type), performed)
        enabled = false
        gate.performHapticFeedback(type)
        assertEquals(listOf(type), performed)
        enabled = true
        gate.performHapticFeedback(type)
        assertEquals(listOf(type, type), performed)
    }
}
