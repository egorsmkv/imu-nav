package org.blinddriver.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin TextToSpeech wrapper; urgent phrases interrupt whatever is being said. */
class Voice(context: Context, private var locale: Locale = Locale.forLanguageTag("uk-UA")) {
    fun setLocale(l: Locale) {
        locale = l
        if (ready) configure()
    }

    private var ready = false
    private val pending = ArrayList<String>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            configure()
            pending.forEach { speak(it, urgent = false) }
            pending.clear()
        }
    }

    private fun configure() {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault())
    }

    fun speak(text: String, urgent: Boolean) {
        if (!ready) {
            pending += text
            return
        }
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() = tts.shutdown()
}
