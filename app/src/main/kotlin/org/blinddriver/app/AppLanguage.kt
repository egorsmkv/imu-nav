package org.blinddriver.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * In-app language choice: follow the phone, or force Ukrainian / English. Stored in prefs and
 * applied to the application and each activity (via attachBaseContext) so it works on every API level.
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val UKRAINIAN = "uk"
    const val ENGLISH = "en"
    val CHOICES = listOf(SYSTEM, UKRAINIAN, ENGLISH)

    private const val PREFS = "language"
    private const val KEY = "tag"

    /** The saved choice: [SYSTEM], [UKRAINIAN] or [ENGLISH]. */
    fun get(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /** The locale in effect for [choice]. */
    fun locale(choice: String): Locale = when (choice) {
        UKRAINIAN -> Locale.forLanguageTag("uk-UA")
        ENGLISH -> Locale.ENGLISH
        else -> Resources.getSystem().configuration.locales[0]
    }

    /** Is the effective language Ukrainian? */
    fun isUkrainian(context: Context) = locale(get(context)).language == "uk"

    /** Context whose resources use the chosen language (for Application/Activity.attachBaseContext). */
    fun wrap(base: Context): Context {
        val choice = get(base)
        val locale = locale(choice)
        Locale.setDefault(locale)
        if (choice == SYSTEM) return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }

    /** Save [choice] and switch the application's resources; activities must be recreated afterwards. */
    fun set(context: Context, choice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, choice) }
        val locale = locale(choice)
        Locale.setDefault(locale)
        val res = context.applicationContext.resources
        val config = Configuration(res.configuration).apply { setLocales(LocaleList(locale)) }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }
}
