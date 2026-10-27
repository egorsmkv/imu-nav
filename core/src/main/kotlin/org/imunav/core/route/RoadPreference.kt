package org.imunav.core.route

/** Car cost variants are prepared independently so each offline hierarchy remains correct. */
enum class RoadPreference(val profile: String) {
    /** Favor through streets while retaining necessary local access. */
    MAIN_ROADS("car"),

    /** Allow useful shortcuts on public streets using the balanced distance/time metric. */
    BALANCED("car_balanced"),
}
