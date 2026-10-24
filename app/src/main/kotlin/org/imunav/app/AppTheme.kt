package org.imunav.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.edit

/**
 * In-app light/dark choice: follow the phone, or force light or dark. Like [AppLanguage] it is
 * applied in attachBaseContext by overriding the night bit of the configuration, so Compose
 * (`isSystemInDarkTheme`), `values-night` resources, the system bars and the map style all agree.
 */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val CHOICES = listOf(SYSTEM, LIGHT, DARK)

    private const val PREFS = "theme"
    private const val KEY = "mode"

    /** The saved choice: [SYSTEM], [LIGHT] or [DARK]. */
    fun get(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)?.takeIf { it in CHOICES } ?: SYSTEM

    /** Save [choice]; activities must be recreated afterward to pick it up. */
    fun set(context: Context, choice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, choice) }
    }

    /** Is the app dark right now (for code without an activity, e.g. choosing a map style to download)? */
    fun isDark(context: Context): Boolean = when (get(context)) {
        LIGHT -> false
        DARK -> true
        else -> (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Context whose resources use the chosen night mode (for Activity.attachBaseContext). */
    fun wrap(base: Context): Context {
        val night = when (get(base)) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val config = Configuration(base.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        return base.createConfigurationContext(config)
    }
}
