package org.imunav.app.sync

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import org.imunav.app.MapStartMode
import org.imunav.app.power.PowerMode
import org.imunav.core.cells.Radio
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.net.WireJson
import org.imunav.core.net.booleanValueChecked
import org.imunav.core.net.elements
import org.imunav.core.net.fields
import org.imunav.core.net.finiteValue
import org.imunav.core.net.nullable
import org.imunav.core.net.textValue
import org.imunav.core.route.TravelMode

/** Decode a whole known-settings snapshot before exposing values to runtime setters. */
internal object PortablePreferenceCodec {
    private val keys = setOf(
        "setting:language",
        "setting:voice",
        "setting:haptics",
        "setting:travel_mode",
        "setting:navigation_method",
        "setting:navigation_estimator",
        "setting:terrain",
        "setting:power_mode",
        "setting:screen_on",
        "setting:offline_map",
        "setting:corridor",
        "setting:online_routing",
        "setting:show_towers",
        "setting:radios",
        "setting:map_start",
    )

    /** Retain future settings verbatim so the next sync does not mistake them for local deletions. */
    fun unknown(values: Map<String, String>): Map<String, String> = values.filterKeys { it.startsWith("setting:") && it !in keys }

    /** Unknown keys stay in the journal, but never enter this version's runtime settings. */
    fun decode(values: Map<String, String>): List<DecodedPreference> = values.mapNotNull { (key, text) ->
        if (key !in keys) return@mapNotNull null
        val preference = WireJson.decode(text) {
            when (key) {
                "setting:language" -> PortablePreference.Language(PortableLanguage.parse(textValue()))
                "setting:voice" -> PortablePreference.Voice(booleanValueChecked())
                "setting:haptics" -> PortablePreference.Haptics(booleanValueChecked())
                "setting:travel_mode" -> PortablePreference.Travel(TravelMode.valueOf(textValue()))
                "setting:navigation_method" -> PortablePreference.Method(NavigationMethod.valueOf(textValue()))
                "setting:navigation_estimator" -> PortablePreference.Estimator(NavigationEstimator.valueOf(textValue()))
                "setting:terrain" -> PortablePreference.Terrain(booleanValueChecked())
                "setting:power_mode" -> PortablePreference.Power(PowerMode.valueOf(textValue()))
                "setting:screen_on" -> PortablePreference.Screen(booleanValueChecked())
                "setting:offline_map" -> PortablePreference.OfflineMap(booleanValueChecked())
                "setting:corridor" -> PortablePreference.Corridor(booleanValueChecked())
                "setting:online_routing" -> PortablePreference.OnlineRouting(booleanValueChecked())
                "setting:show_towers" -> PortablePreference.Towers(booleanValueChecked())
                "setting:radios" -> PortablePreference.Radios(elements { Radio.valueOf(textValue()) }.toSet())
                "setting:map_start" -> PortablePreference.MapStart(mapStart())
                else -> error("Unsupported synchronized preference")
            }
        }
        DecodedPreference(preference, text)
    }

    /** Canonical output is kept compatible with existing sync baselines. */
    fun encode(value: PortablePreference): String = when (value) {
        is PortablePreference.Language -> SyncCodec.canonical(value.value.wireValue)

        is PortablePreference.Voice -> SyncCodec.canonical(value.value)

        is PortablePreference.Haptics -> SyncCodec.canonical(value.value)

        is PortablePreference.Travel -> SyncCodec.canonical(value.value.name)

        is PortablePreference.Method -> SyncCodec.canonical(value.value.name)

        is PortablePreference.Estimator -> SyncCodec.canonical(value.value.name)

        is PortablePreference.Terrain -> SyncCodec.canonical(value.value)

        is PortablePreference.Power -> SyncCodec.canonical(value.value.name)

        is PortablePreference.Screen -> SyncCodec.canonical(value.value)

        is PortablePreference.OfflineMap -> SyncCodec.canonical(value.value)

        is PortablePreference.Corridor -> SyncCodec.canonical(value.value)

        is PortablePreference.OnlineRouting -> SyncCodec.canonical(value.value)

        is PortablePreference.Towers -> SyncCodec.canonical(value.value)

        is PortablePreference.Radios -> WireJson.encode {
            writeStartArray()
            value.value.sortedBy { it.name }.forEach { writeString(it.name) }
            writeEndArray()
        }

        is PortablePreference.MapStart -> WireJson.encode { writeMapStart(value.value) }
    }

    private fun JsonParser.mapStart(): PortableMapStart {
        var mode: MapStartMode? = null
        var point: GeoPoint? = null
        fields { field ->
            when (field) {
                "mode" -> mode = MapStartMode.valueOf(textValue())
                "point" -> point = nullable { point() }
                else -> skipChildren()
            }
        }
        return PortableMapStart.of(requireNotNull(mode), point)
    }

    private fun JsonParser.point(): GeoPoint {
        var lat: Double? = null
        var lon: Double? = null
        fields { field ->
            when (field) {
                "lat" -> lat = finiteValue().also { require(it in -90.0..90.0) }
                "lon" -> lon = finiteValue().also { require(it in -180.0..180.0) }
                else -> skipChildren()
            }
        }
        return GeoPoint(requireNotNull(lat), requireNotNull(lon))
    }

    private fun JsonGenerator.writeMapStart(value: PortableMapStart) {
        val mode = when (value) {
            is PortableMapStart.Gps -> MapStartMode.GPS
            is PortableMapStart.Fixed -> MapStartMode.FIXED
        }
        val point = when (value) {
            is PortableMapStart.Gps -> value.savedPoint
            is PortableMapStart.Fixed -> value.point
        }
        writeStartObject()
        writeStringField("mode", mode.name)
        writeFieldName("point")
        if (point == null) {
            writeNull()
        } else {
            require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
            writeStartObject()
            writeNumberField("lat", point.lat)
            writeNumberField("lon", point.lon)
            writeEndObject()
        }
        writeEndObject()
    }
}
