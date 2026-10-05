package org.imunav.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.Tuning
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.TravelMode

/** Stores navigation choices while AppGraph enforces when a choice may change. */
internal class NavigationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("travel", Context.MODE_PRIVATE)

    val travelMode = MutableStateFlow(TravelMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: TravelMode.CAR)
    val navigationMethod = MutableStateFlow(NavigationMethod.entries.firstOrNull { it.name == prefs.getString("navigation_method", null) } ?: NavigationMethod.HYBRID)
    private val _navigationEstimator =
        MutableStateFlow(NavigationEstimator.entries.firstOrNull { it.name == prefs.getString("navigation_estimator", null) } ?: NavigationEstimator.NATIVE_KALMAN)
    val navigationEstimator: StateFlow<NavigationEstimator> = _navigationEstimator.asStateFlow()
    private val _inertialExperiment = MutableStateFlow(prefs.getBoolean("inertial_experiment", false))
    val inertialExperiment: StateFlow<Boolean> = _inertialExperiment.asStateFlow()
    val tuning = MutableStateFlow(Tuning.DEFAULT.copy(terrainMatch = prefs.getBoolean("terrain_match", true)))

    fun setTravelMode(mode: TravelMode) {
        prefs.edit { putString("mode", mode.name) }
        travelMode.value = mode
    }

    fun setNavigationMethod(method: NavigationMethod) {
        prefs.edit { putString("navigation_method", method.name) }
        navigationMethod.value = method
    }

    fun setInertialExperiment(enabled: Boolean) {
        prefs.edit { putBoolean("inertial_experiment", enabled) }
        _inertialExperiment.value = enabled
    }

    fun setNavigationEstimator(estimator: NavigationEstimator) {
        prefs.edit { putString("navigation_estimator", estimator.name) }
        _navigationEstimator.value = estimator
    }

    fun setTerrainMatch(enabled: Boolean) {
        prefs.edit { putBoolean("terrain_match", enabled) }
        tuning.value = tuning.value.copy(terrainMatch = enabled)
    }
}
