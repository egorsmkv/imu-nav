package org.imunav.app.maps

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.nav.MapDisplayOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A separate preference file checks process-recreation semantics without changing app settings. */
@RunWith(AndroidJUnit4::class)
class MapDisplayPreferencesTest {
    @Test
    fun defaultsAndIndependentChangesSurviveRecreation() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("test_map_display", mode)
        }
        val prefs = context.getSharedPreferences("test_map_display", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        try {
            val preferences = MapDisplayPreferences(context)
            assertEquals(MapDisplayOptions(), preferences.options.value)
            preferences.update { it.copy(buildings3d = false) }
            preferences.update { it.copy(carPerspective = false) }
            preferences.update { it.copy(automaticZoom = false) }
            preferences.update { it.copy(hideTowerButton = true) }
            preferences.update { it.copy(hideZoomButtons = true) }
            val expected = MapDisplayOptions(
                buildings3d = false,
                carPerspective = false,
                automaticZoom = false,
                hideTowerButton = true,
                hideZoomButtons = true,
            )
            assertEquals(expected, preferences.options.value)
            assertEquals(expected, MapDisplayPreferences(context).options.value)
        } finally {
            prefs.edit { clear() }
        }
    }
}
