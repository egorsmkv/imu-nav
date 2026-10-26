package org.imunav.app.sync

import org.imunav.app.AppGraph
import org.imunav.app.MapStartMode
import org.imunav.core.cells.Radio

/** Allowlisted user choices, applied through normal setters after navigation becomes idle. */
internal class PortableSettings(private val app: AppGraph) {
    fun capture(retained: Map<String, String> = emptyMap()): Map<String, String> = PortablePreferenceCodec.unknown(retained) + listOf(
        PortablePreference.Language(PortableLanguage.parse(app.language.value)),
        PortablePreference.Voice(app.voiceEnabled.value),
        PortablePreference.Haptics(app.haptics.enabled.value),
        PortablePreference.Travel(app.travelMode.value),
        PortablePreference.Method(app.navigationMethod.value),
        PortablePreference.Estimator(app.navigationEstimator.value),
        PortablePreference.Terrain(app.tuning.value.terrainMatch),
        PortablePreference.Power(app.powerMode.value),
        PortablePreference.Screen(app.keepScreenOn.value),
        PortablePreference.OfflineMap(app.offlineMap.status.value.useOffline),
        PortablePreference.Corridor(app.offlineMap.status.value.corridor),
        PortablePreference.OnlineRouting(app.offlineRouting.status.value.allowOnline),
        PortablePreference.Towers(app.cells.status.value.showTowers),
        PortablePreference.Radios(app.cells.enabledRadios().toSet()),
        PortablePreference.MapStart(PortableMapStart.of(app.mapStartMode.value, app.mapStartFixed.value)),
    ).associate { it.key to PortablePreferenceCodec.encode(it) }

    fun decode(values: Map<String, String>): List<DecodedPreference> = PortablePreferenceCodec.decode(values)

    /** Changes are selected before any setters run; travel mode stays last because it can start planning. */
    fun apply(values: List<DecodedPreference>) {
        val byKey = values.associateBy { it.preference.key }
        val encoded = values.associate { it.preference.key to it.encodedValue }
        preferenceChanges(capture(), encoded).forEach { change -> apply(byKey.getValue(change.key).preference) }
    }

    private fun apply(value: PortablePreference) {
        when (value) {
            is PortablePreference.Language -> app.setLanguage(value.value.wireValue)

            is PortablePreference.Voice -> app.setVoiceEnabled(value.value)

            is PortablePreference.Haptics -> app.haptics.setEnabled(value.value)

            is PortablePreference.Travel -> app.setTravelMode(value.value)

            is PortablePreference.Method -> app.setNavigationMethod(value.value)

            is PortablePreference.Estimator -> app.setNavigationEstimator(value.value)

            is PortablePreference.Terrain -> app.setTerrainMatch(value.value)

            is PortablePreference.Power -> app.setPowerMode(value.value)

            is PortablePreference.Screen -> app.setKeepScreenOn(value.value)

            is PortablePreference.OfflineMap -> app.offlineMap.setUseOffline(value.value)

            is PortablePreference.Corridor -> app.offlineMap.setCorridorEnabled(value.value)

            is PortablePreference.OnlineRouting -> app.offlineRouting.setAllowOnline(value.value)

            is PortablePreference.Towers -> app.cells.setShowTowers(value.value)

            is PortablePreference.Radios -> Radio.entries.forEach { app.cells.setRadioEnabled(it, it in value.value) }

            is PortablePreference.MapStart -> when (val start = value.value) {
                is PortableMapStart.Gps -> app.setMapStart(MapStartMode.GPS, start.savedPoint)
                is PortableMapStart.Fixed -> app.setMapStart(MapStartMode.FIXED, start.point)
            }
        }
    }
}
