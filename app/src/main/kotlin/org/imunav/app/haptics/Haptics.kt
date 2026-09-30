package org.imunav.app.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.nav.NavAlert
import java.util.concurrent.Executors

/**
 * Navigation vibrations: each [NavAlert] has its own pattern, so a driver can tell "turn now"
 * from "GPS lost" by feel, even with the sound off or in a loud car.
 *
 * Button taps use Compose's `LocalHapticFeedback` instead (it follows the phone's touch-feedback
 * setting). The same saved switch gates those taps and the navigation alerts managed here.
 */
class Haptics(context: Context) {
    private val prefs = context.getSharedPreferences("haptics", Context.MODE_PRIVATE)

    private val vibratorDelegate = lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }
    private val vibrator: Vibrator? by vibratorDelegate

    /** Talking to the vibrator service is a system call; keep it off the main (UI) thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "haptics") }

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))

    /** App-wide haptic feedback preference, including UI taps. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Stop active navigation feedback on disable; queued alerts also recheck the current preference. */
    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean("enabled", value) }
        _enabled.value = value
        if (!value) worker.execute { runCatching { if (vibratorDelegate.isInitialized()) vibrator?.cancel() } }
    }

    /** Vibrate the pattern for [alert] (does nothing when switched off or without a vibrator). */
    fun play(alert: NavAlert) {
        if (!_enabled.value) return
        val timings = patternFor(alert)
        worker.execute {
            if (!_enabled.value) return@execute
            // Vibration is optional: a missing/unavailable system service must not stop navigation.
            runCatching {
                val v = vibrator ?: return@runCatching
                if (!v.hasVibrator()) return@runCatching
                // -1 = play once, no repeat.
                val effect = VibrationEffect.createWaveform(timings, -1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
                } else {
                    @Suppress("DEPRECATION") // the replacement (VibrationAttributes) needs Android 13
                    v.vibrate(effect, NAVIGATION_AUDIO)
                }
            }
        }
    }

    companion object {
        private val NAVIGATION_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /**
         * Vibration patterns as `[pause, buzz, pause, buzz, …]` in milliseconds.
         * Rule of thumb: the more urgent, the more and stronger buzzes.
         */
        fun patternFor(alert: NavAlert): LongArray = when (alert) {
            NavAlert.TURN_SOON -> longArrayOf(0, 120)
            NavAlert.TURN_NOW -> longArrayOf(0, 220, 120, 220)
            NavAlert.OFF_ROUTE -> longArrayOf(0, 90, 80, 90, 80, 90)
            NavAlert.REROUTED -> longArrayOf(0, 60)
            NavAlert.GPS_LOST -> longArrayOf(0, 450, 150, 100)
            NavAlert.GPS_RESTORED -> longArrayOf(0, 60, 100, 60)
            NavAlert.ARRIVED -> longArrayOf(0, 150, 100, 150, 100, 450)
        }
    }
}
