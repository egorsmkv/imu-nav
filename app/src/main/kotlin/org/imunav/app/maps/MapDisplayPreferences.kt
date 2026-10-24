package org.imunav.app.maps

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.nav.MapDisplayOptions

/** Saves display choices separately from navigation inputs and publishes them immediately. */
class MapDisplayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("map_display", Context.MODE_PRIVATE)
    private val defaults = MapDisplayOptions()
    private val mutableOptions = MutableStateFlow(
        MapDisplayOptions(
            buildings3d = prefs.getBoolean("buildings_3d", defaults.buildings3d),
            carPerspective = prefs.getBoolean("car_perspective", defaults.carPerspective),
            automaticZoom = prefs.getBoolean("automatic_zoom", defaults.automaticZoom),
            hideTowerButton = prefs.getBoolean("hide_tower_button", defaults.hideTowerButton),
            hideZoomButtons = prefs.getBoolean("hide_zoom_buttons", defaults.hideZoomButtons),
            hideLocationOffWarning = prefs.getBoolean("hide_location_off_warning", defaults.hideLocationOffWarning),
        ),
    )
    val options = mutableOptions.asStateFlow()

    /** Transform the latest snapshot so independent switches cannot overwrite one another. */
    fun update(transform: (MapDisplayOptions) -> MapDisplayOptions) {
        val updated = transform(mutableOptions.value)
        prefs.edit {
            putBoolean("buildings_3d", updated.buildings3d)
            putBoolean("car_perspective", updated.carPerspective)
            putBoolean("automatic_zoom", updated.automaticZoom)
            putBoolean("hide_tower_button", updated.hideTowerButton)
            putBoolean("hide_zoom_buttons", updated.hideZoomButtons)
            putBoolean("hide_location_off_warning", updated.hideLocationOffWarning)
        }
        mutableOptions.value = updated
    }
}
