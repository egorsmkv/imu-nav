package org.imunav.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.R
import org.imunav.app.maps.MapDisplayPreferences
import org.imunav.core.nav.NavigationMapCamera
import kotlin.math.roundToInt

/** Phone map presentation and optional controls while a route is active. */
@Composable
internal fun MapDisplaySettings(preferences: MapDisplayPreferences) {
    val options by preferences.options.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.map_display_title))
    SwitchItem(stringResource(R.string.map_buildings_3d), stringResource(R.string.map_buildings_3d_hint), options.buildings3d) { checked ->
        preferences.update { it.copy(buildings3d = checked) }
    }
    SwitchItem(stringResource(R.string.map_direction_arrow), stringResource(R.string.map_direction_arrow_hint), options.directionArrow) { checked ->
        preferences.update { it.copy(directionArrow = checked) }
    }
    SwitchItem(stringResource(R.string.map_car_perspective), stringResource(R.string.map_car_perspective_hint), options.carPerspective) { checked ->
        preferences.update { it.copy(carPerspective = checked) }
    }
    if (options.carPerspective) CarTiltSlider(options.carTiltDeg) { degrees -> preferences.update { it.copy(carTiltDeg = degrees) } }
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

/**
 * Driving tilt in steps of [TILT_STEP_DEG]: more tilt shows more road ahead, less keeps labels
 * flatter. Saved when the finger lifts, so dragging does not write preferences every frame.
 */
@Composable
private fun CarTiltSlider(savedDeg: Double, onSave: (Double) -> Unit) {
    var degrees by remember(savedDeg) { mutableFloatStateOf(savedDeg.toFloat()) }
    val min = NavigationMapCamera.MIN_CAR_TILT_DEG.toFloat()
    val max = NavigationMapCamera.MAX_CAR_TILT_DEG.toFloat()
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_car_tilt, degrees.roundToInt())) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.map_car_tilt_hint))
                Slider(
                    value = degrees,
                    onValueChange = { degrees = it },
                    onValueChangeFinished = { onSave(degrees.toDouble()) },
                    valueRange = min..max,
                    steps = ((max - min) / TILT_STEP_DEG).roundToInt() - 1,
                )
            }
        },
    )
}

private const val TILT_STEP_DEG = 5f
