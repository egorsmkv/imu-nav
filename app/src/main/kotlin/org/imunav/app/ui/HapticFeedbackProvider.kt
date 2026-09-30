package org.imunav.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.imunav.app.haptics.Haptics

/** Read the preference at every tap, even if a MapView listener retained this object before it changed. */
internal class GatedHapticFeedback(private val systemFeedback: HapticFeedback, private val enabled: () -> Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) systemFeedback.performHapticFeedback(hapticFeedbackType)
    }
}

/** Gate all Compose feedback with the app switch while preserving Android's own touch-feedback policy. */
@Composable
internal fun HapticFeedbackProvider(haptics: Haptics, content: @Composable () -> Unit) {
    val systemFeedback = LocalHapticFeedback.current
    val feedback = remember(systemFeedback, haptics) { GatedHapticFeedback(systemFeedback) { haptics.enabled.value } }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}
