package org.imunav.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Speaks navigation prompts with Android's built-in text-to-speech engine.
 *
 * Every call into the engine is a request to another process that can take hundreds of ms
 * (choosing a voice for a language especially), so all of them run on one background thread
 * ([worker]) — never on the main thread — which also keeps prompts in order. The engine takes a
 * moment to start; anything said before it is ready waits in [pending].
 */
class Voice(context: Context, private var locale: Locale = Locale.forLanguageTag("uk-UA"), private var enabled: Boolean = true) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "voice") }

    // State below is only touched on [worker].
    private var ready = false
    private val pending = ArrayList<Pair<String, Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        worker.execute {
            if (status != TextToSpeech.SUCCESS) return@execute
            ready = true
            applyLanguage()
            pending.forEach { (text, urgent) -> say(text, urgent) }
            pending.clear()
        }
    }

    /** Switch the spoken language (e.g. after the user changed it in Settings). */
    fun setLocale(newLocale: Locale) = worker.execute {
        locale = newLocale
        if (ready) applyLanguage()
    }

    /** Enable spoken prompts, or stop and discard speech immediately when disabled. */
    fun setEnabled(value: Boolean) = worker.execute {
        enabled = value
        if (!value) {
            pending.clear()
            if (ready) tts.stop()
        }
    }

    /** Use [locale]; if the phone has no voice for it, fall back to the phone's own language. */
    private fun applyLanguage() {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault())
    }

    /**
     * Say [text]. An [urgent] phrase ("turn left now") interrupts whatever is being said;
     * others wait their turn. Returns immediately; speaking happens on the voice thread.
     */
    fun speak(text: String, urgent: Boolean) = worker.execute {
        if (!enabled) return@execute
        if (!ready) {
            pending += text to urgent
            return@execute
        }
        say(text, urgent)
    }

    private fun say(text: String, urgent: Boolean) {
        val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, queueMode, null, text.hashCode().toString())
    }

    /** End a trip without leaving queued instructions speaking over another navigation app. */
    fun stop() = worker.execute {
        pending.clear()
        if (ready) tts.stop()
    }

    /** Release the text-to-speech engine. */
    fun shutdown() = worker.execute { tts.shutdown() }
}
