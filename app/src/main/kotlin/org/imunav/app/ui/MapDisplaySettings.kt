package org.imunav.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.R
import org.imunav.app.maps.MapDisplayPreferences

/** Phone map presentation and optional controls while a route is active. */
@Composable
internal fun MapDisplaySettings(preferences: MapDisplayPreferences) {
    val options by preferences.options.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.map_display_title))
    SwitchItem(stringResource(R.string.map_buildings_3d), stringResource(R.string.map_buildings_3d_hint), options.buildings3d) { checked ->
        preferences.update { it.copy(buildings3d = checked) }
    }
    SwitchItem(stringResource(R.string.map_car_perspective), stringResource(R.string.map_car_perspective_hint), options.carPerspective) { checked ->
        preferences.update { it.copy(carPerspective = checked) }
    }
    SwitchItem(stringResource(R.string.map_automatic_zoom), stringResource(R.string.map_automatic_zoom_hint), options.automaticZoom) { checked ->
        preferences.update { it.copy(automaticZoom = checked) }
    }
    SwitchItem(stringResource(R.string.map_hide_tower_button), stringResource(R.string.map_hide_tower_button_hint), options.hideTowerButton) { checked ->
        preferences.update { it.copy(hideTowerButton = checked) }
    }
    SwitchItem(stringResource(R.string.map_hide_zoom_buttons), stringResource(R.string.map_hide_zoom_buttons_hint), options.hideZoomButtons) { checked ->
        preferences.update { it.copy(hideZoomButtons = checked) }
    }
    SwitchItem(stringResource(R.string.map_hide_location_warning), stringResource(R.string.map_hide_location_warning_hint), options.hideLocationOffWarning) { checked ->
        preferences.update { it.copy(hideLocationOffWarning = checked) }
    }
}
