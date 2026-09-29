package org.imunav.core.route

/**
 * How the user travels. It selects the routing profile (which ways may be used, how fast) and
 * how the engine estimates movement without GPS.
 *
 * @param profile the GraphHopper profile name in the routing pack
 */
enum class TravelMode(val profile: String) {
    /** By car: roads only; dead reckoning from speed limits, IMU and cell towers. */
    CAR("car"),

    /** On foot: footways, paths, steps and pedestrian streets too; dead reckoning from the step counter. */
    FOOT("foot"),
}
