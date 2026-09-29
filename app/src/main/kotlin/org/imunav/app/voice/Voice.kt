package org.imunav.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks navigation prompts with Android's built-in text-to-speech engine.
 *
 * The engine takes a moment to start. Anything said before it is ready is queued in [pending]
 * and spoken as soon as it is.
 */
class Voice(context: Context, private var locale: Locale = Locale.forLanguageTag("uk-UA"), private var enabled: Boolean = true) {
    private var ready = false
    private val pending = ArrayList<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyLanguage()
            pending.forEach { speak(it, urgent = false) }
            pending.clear()
        }
    }

    /** Switch the spoken language (e.g. after the user changed it in Settings). */
    fun setLocale(newLocale: Locale) {
        locale = newLocale
        if (ready) applyLanguage()
    }

    /** Enable spoken prompts, or stop and discard speech immediately when disabled. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            pending.clear()
            tts.stop()
        }
    }

    /** Use [locale]; if the phone has no voice for it, fall back to the phone's own language. */
    private fun applyLanguage() {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault())
    }

    /**
     * Say [text]. An [urgent] phrase ("turn left now") interrupts whatever is being said;
     * others wait their turn.
     */
    fun speak(text: String, urgent: Boolean) {
        if (!enabled) return
        if (!ready) {
            pending += text
            return
        }
        val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, queueMode, null, text.hashCode().toString())
    }

    /** Release the text-to-speech engine. */
    fun shutdown() = tts.shutdown()
}
