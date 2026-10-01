package org.imunav.core.nav

import org.imunav.core.route.TravelMode

/** Shared voice/haptic state; either position owner must announce from the same published progress. */
internal class GuidanceAnnouncer(private val listener: NavListener, private val say: (String, Boolean) -> Unit) {
    private val announced = HashSet<Int>()
    private var gpsLostAnnounced = false
    private var arrivedAnnounced = false

    fun resetTrip() {
        gpsLostAnnounced = false
    }

    fun resetRoute() {
        announced.clear()
        arrivedAnnounced = false
    }

    /** Arrival and GPS transitions precede maneuver announcements, with one alert per distance level. */
    fun announce(state: GuidanceState, phrases: Phrases, speedMps: Double, gpsUsed: Boolean) {
        if (state.arrived) {
            if (!arrivedAnnounced) {
                arrivedAnnounced = true
                listener.onAlert(NavAlert.ARRIVED)
                say(phrases.arrived(), false)
            }
            return
        }
        if (state.source.isGps && gpsLostAnnounced) {
            gpsLostAnnounced = false
            listener.onAlert(NavAlert.GPS_RESTORED)
            say(phrases.gpsRestored(), false)
        } else if (!state.source.isGps && state.source != PositionSource.NONE && gpsUsed && !gpsLostAnnounced && state.blindS >= 5) {
            gpsLostAnnounced = true
            listener.onAlert(NavAlert.GPS_LOST)
            say(phrases.gpsLost(), false)
        }
        val step = state.nextStep
        val next = state.nextStepIndex
        if (step == null || next < 0) return
        val announceAt = if (state.travelMode == TravelMode.FOOT) WALK_ANNOUNCE_AT_M else ANNOUNCE_AT_M
        val level = announceAt.indexOfLast { state.distToNextM <= it }
        if (level < 0) return
        // Each (step, distance level) is announced once; key = step × 10 + level.
        val key = next * 10 + level
        if (key in announced) return
        for (skipped in 0..level) announced += next * 10 + skipped
        // "In 1 km…" is pointless in slow city traffic (below 50 km/h).
        if (state.travelMode == TravelMode.CAR && level == 0 && speedMps < 14.0) return
        when (level) {
            announceAt.lastIndex -> listener.onAlert(NavAlert.TURN_NOW)
            announceAt.lastIndex - 1 -> listener.onAlert(NavAlert.TURN_SOON)
        }
        say(phrases.maneuver(step, if (level == announceAt.lastIndex) null else state.distToNextM), level >= 2)
    }

    private companion object {
        val ANNOUNCE_AT_M = listOf(1000.0, 400.0, 150.0, 40.0)
        val WALK_ANNOUNCE_AT_M = listOf(150.0, 50.0, 15.0)
    }
}
