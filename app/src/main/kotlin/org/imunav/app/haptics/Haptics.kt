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
 * setting); this class is only for alerts during navigation and can be switched off in Settings.
 */
class Haptics(context: Context) {
    private val prefs = context.getSharedPreferences("haptics", Context.MODE_PRIVATE)

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    /** Talking to the vibrator service is a system call; keep it off the main (UI) thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "haptics") }

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))

    /** "Vibrate on turns and alerts" in Settings. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val available: Boolean get() = vibrator?.hasVibrator() == true

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean("enabled", value) }
        _enabled.value = value
    }

    /** Vibrate the pattern for [alert] (does nothing when switched off or without a vibrator). */
    fun play(alert: NavAlert) {
        if (!_enabled.value) return
        val v = vibrator ?: return
        val timings = patternFor(alert)
        worker.execute {
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
