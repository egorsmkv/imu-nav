package org.imunav.app.sync

import org.imunav.app.AppGraph
import org.imunav.app.MapStartMode
import org.imunav.app.power.PowerMode
import org.imunav.core.cells.Radio
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.TravelMode
import org.json.JSONArray
import org.json.JSONObject

/** Allowlisted user choices, applied through normal setters after navigation becomes idle. */
internal class PortableSettings(private val app: AppGraph) {
    fun capture(): Map<String, String> = mapOf(
        "language" to app.language.value,
        "voice" to app.voiceEnabled.value,
        "haptics" to app.haptics.enabled.value,
        "travel_mode" to app.travelMode.value.name,
        "navigation_method" to app.navigationMethod.value.name,
        "navigation_estimator" to app.navigationEstimator.value.name,
        "terrain" to app.tuning.value.terrainMatch,
        "power_mode" to app.powerMode.value.name,
        "screen_on" to app.keepScreenOn.value,
        "offline_map" to app.offlineMap.status.value.useOffline,
        "corridor" to app.offlineMap.status.value.corridor,
        "online_routing" to app.offlineRouting.status.value.allowOnline,
        "show_towers" to app.cells.status.value.showTowers,
        "radios" to JSONArray(app.cells.enabledRadios().sortedBy { it.name }.map { it.name }),
        "map_start" to JSONObject().put("mode", app.mapStartMode.value.name).put("point", app.mapStartFixed.value?.let(SyncCodec::point) ?: JSONObject.NULL),
    ).mapKeys { "setting:${it.key}" }.mapValues { SyncCodec.canonical(it.value) }

    /** Validate the entire snapshot before changing any runtime state. */
    fun validate(values: Map<String, String>) {
        values.filterKeys { it.startsWith("setting:") }.forEach { (key, text) ->
            val value = SyncCodec.parse(text)
            val valid = when (key.removePrefix("setting:")) {
                "language" -> value in listOf("system", "en", "uk", "ru")

                "travel_mode" -> TravelMode.entries.any { it.name == value }

                "navigation_method" -> NavigationMethod.entries.any { it.name == value }

                "navigation_estimator" -> NavigationEstimator.entries.any { it.name == value }

                "power_mode" -> PowerMode.entries.any { it.name == value }

                "radios" -> value is JSONArray && (0 until value.length()).all { index -> Radio.entries.any { it.name == value.get(index) } }

                "map_start" -> value is JSONObject && MapStartMode.entries.any { it.name == value.getString("mode") } &&
                    ((value.isNull("point") && value.getString("mode") == "GPS") || (!value.isNull("point") && SyncCodec.point(value.getJSONObject("point")).lat.isFinite()))

                else -> value is Boolean
            }
            require(valid) { "Invalid synchronized preference" }
        }
    }

    fun apply(values: Map<String, String>) {
        val current = capture()
        val changes = preferenceChanges(current, values)
        changes.forEach { (key, text) ->
            val value = SyncCodec.parse(text)
            when (key.removePrefix("setting:")) {
                "language" -> app.setLanguage(value as String)

                "voice" -> app.setVoiceEnabled(value as Boolean)

                "haptics" -> app.haptics.setEnabled(value as Boolean)

                "travel_mode" -> app.setTravelMode(TravelMode.valueOf(value as String))

                "navigation_method" -> app.setNavigationMethod(NavigationMethod.valueOf(value as String))

                "navigation_estimator" -> app.setNavigationEstimator(NavigationEstimator.valueOf(value as String))

                "terrain" -> app.setTerrainMatch(value as Boolean)

                "power_mode" -> app.setPowerMode(PowerMode.valueOf(value as String))

                "screen_on" -> app.setKeepScreenOn(value as Boolean)

                "offline_map" -> app.offlineMap.setUseOffline(value as Boolean)

                "corridor" -> app.offlineMap.setCorridorEnabled(value as Boolean)

                "online_routing" -> app.offlineRouting.setAllowOnline(value as Boolean)

                "show_towers" -> app.cells.setShowTowers(value as Boolean)

                "radios" -> Radio.entries.forEach {
                    app.cells.setRadioEnabled(
                        it,
                        (value as JSONArray).let { radios ->
                            (0 until radios.length()).any { index ->
                                radios.getString(index) ==
                                    it.name
                            }
                        },
                    )
                }

                "map_start" -> (value as JSONObject).let {
                    app.setMapStart(MapStartMode.valueOf(it.getString("mode")), if (it.isNull("point")) null else SyncCodec.point(it.getJSONObject("point")))
                }
            }
        }
    }
}
