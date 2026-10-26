package org.imunav.app.sync

import org.imunav.app.MapStartMode
import org.imunav.app.power.PowerMode
import org.imunav.core.cells.Radio
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.TravelMode

/** Stored language identifiers are distinct from arbitrary display strings. */
internal enum class PortableLanguage(val wireValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    UKRAINIAN("uk"),
    RUSSIAN("ru"),
    ;

    companion object {
        fun parse(value: String): PortableLanguage = requireNotNull(entries.find { it.wireValue == value }) { "Invalid synchronized language" }
    }
}

/** GPS mode may retain a remembered fixed point; fixed mode always needs an actual point. */
internal sealed interface PortableMapStart {
    /** A remembered point is not an authoritative positioning input. */
    data class Gps(val savedPoint: GeoPoint?) : PortableMapStart

    /** Fixed map startup cannot be configured without coordinates. */
    data class Fixed(val point: GeoPoint) : PortableMapStart

    companion object {
        fun of(mode: MapStartMode, point: GeoPoint?): PortableMapStart = when (mode) {
            MapStartMode.GPS -> Gps(point)
            MapStartMode.FIXED -> Fixed(requireNotNull(point))
        }
    }
}

/** Each preference owns its key and value type, preventing mismatched setter arguments. */
internal sealed interface PortablePreference {
    val key: String

    /** Typed language choice. */
    data class Language(val value: PortableLanguage) : PortablePreference {
        override val key = "setting:language"
    }

    /** Typed voice choice. */
    data class Voice(val value: Boolean) : PortablePreference {
        override val key = "setting:voice"
    }

    /** Typed haptics choice. */
    data class Haptics(val value: Boolean) : PortablePreference {
        override val key = "setting:haptics"
    }

    /** Typed travel mode choice. */
    data class Travel(val value: TravelMode) : PortablePreference {
        override val key = "setting:travel_mode"
    }

    /** Typed navigation method choice. */
    data class Method(val value: NavigationMethod) : PortablePreference {
        override val key = "setting:navigation_method"
    }

    /** Typed navigation estimator choice. */
    data class Estimator(val value: NavigationEstimator) : PortablePreference {
        override val key = "setting:navigation_estimator"
    }

    /** Typed terrain choice. */
    data class Terrain(val value: Boolean) : PortablePreference {
        override val key = "setting:terrain"
    }

    /** Typed power mode choice. */
    data class Power(val value: PowerMode) : PortablePreference {
        override val key = "setting:power_mode"
    }

    /** Typed screen on choice. */
    data class Screen(val value: Boolean) : PortablePreference {
        override val key = "setting:screen_on"
    }

    /** Typed offline map choice. */
    data class OfflineMap(val value: Boolean) : PortablePreference {
        override val key = "setting:offline_map"
    }

    /** Typed corridor choice. */
    data class Corridor(val value: Boolean) : PortablePreference {
        override val key = "setting:corridor"
    }

    /** Typed online routing choice. */
    data class OnlineRouting(val value: Boolean) : PortablePreference {
        override val key = "setting:online_routing"
    }

    /** Typed show towers choice. */
    data class Towers(val value: Boolean) : PortablePreference {
        override val key = "setting:show_towers"
    }

    /** Typed radios choice. */
    data class Radios(val value: Set<Radio>) : PortablePreference {
        override val key = "setting:radios"
    }

    /** Typed map start choice. */
    data class MapStart(val value: PortableMapStart) : PortablePreference {
        override val key = "setting:map_start"
    }
}

/** Original encoding preserves existing change detection while application uses the validated value. */
internal data class DecodedPreference(val preference: PortablePreference, val encodedValue: String)
