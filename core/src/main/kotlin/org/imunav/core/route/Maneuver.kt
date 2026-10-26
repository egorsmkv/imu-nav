package org.imunav.core.route

/** Closed maneuver vocabulary keeps routing, recorded routes and guidance in agreement. */
enum class ManeuverType(val wireValue: String) {
    DEPART("depart"),
    ARRIVE("arrive"),
    TURN("turn"),
    NEW_NAME("new name"),
    MERGE("merge"),
    ON_RAMP("on ramp"),
    OFF_RAMP("off ramp"),
    FORK("fork"),
    END_OF_ROAD("end of road"),
    CONTINUE("continue"),
    ROUNDABOUT("roundabout"),
    ROTARY("rotary"),
    ROUNDABOUT_TURN("roundabout turn"),
    EXIT_ROUNDABOUT("exit roundabout"),
    EXIT_ROTARY("exit rotary"),
    NOTIFICATION("notification"),
    USE_LANE("use lane"),
    ;

    companion object {
        /** Unknown values cannot silently select an unrelated guidance instruction. */
        fun parse(value: String): ManeuverType = requireNotNull(entries.find { it.wireValue == value }) { "Unsupported maneuver type" }
    }
}

/** Direction is independent of maneuver kind; absent directions remain absent at boundaries. */
enum class ManeuverModifier(val wireValue: String) {
    STRAIGHT("straight"),
    UTURN("uturn"),
    LEFT("left"),
    RIGHT("right"),
    SLIGHT_LEFT("slight left"),
    SLIGHT_RIGHT("slight right"),
    SHARP_LEFT("sharp left"),
    SHARP_RIGHT("sharp right"),
    ;

    val isLeft: Boolean get() = this == LEFT || this == SLIGHT_LEFT || this == SHARP_LEFT
    val isRight: Boolean get() = this == RIGHT || this == SLIGHT_RIGHT || this == SHARP_RIGHT

    companion object {
        /** Empty legacy modifiers mean absent; other unknown values are rejected. */
        fun parse(value: String?): ManeuverModifier? = value?.takeIf { it.isNotEmpty() }?.let { text ->
            requireNotNull(entries.find { it.wireValue == text }) { "Unsupported maneuver modifier" }
        }
    }
}
