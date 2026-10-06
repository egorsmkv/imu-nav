package org.imunav.app.ui

import org.imunav.core.route.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** The map and car cues should interpret OSRM's turn vocabulary consistently. */
class ManeuverTest {
    @Test
    fun mapsJunctionsAndRampsToTheSideTheDriverMustTake() {
        val cases = listOf(
            Step("fork", "slight left") to Maneuver.KEEP_LEFT,
            Step("fork", "slight right") to Maneuver.KEEP_RIGHT,
            Step("on ramp", "left") to Maneuver.RAMP_LEFT,
            Step("off ramp", "right") to Maneuver.RAMP_RIGHT,
            Step("on ramp") to Maneuver.STRAIGHT,
            Step("merge", "right") to Maneuver.MERGE,
        )
        cases.forEach { (step, expected) -> assertEquals(step.toString(), expected, maneuverOf(step)) }
    }

    @Test
    fun mapsRoundaboutsArrivalsAndTurnModifiers() {
        val cases = listOf(
            Step("roundabout") to Maneuver.ROUNDABOUT,
            Step("exit rotary") to Maneuver.ROUNDABOUT,
            Step("arrive") to Maneuver.ARRIVE,
            Step("turn", "uturn") to Maneuver.UTURN,
            Step("turn", "sharp left") to Maneuver.SHARP_LEFT,
            Step("turn", "sharp right") to Maneuver.SHARP_RIGHT,
            Step("turn", "slight left") to Maneuver.SLIGHT_LEFT,
            Step("turn", "slight right") to Maneuver.SLIGHT_RIGHT,
            Step("turn", "left") to Maneuver.LEFT,
            Step("turn", "right") to Maneuver.RIGHT,
            Step("continue") to Maneuver.STRAIGHT,
        )
        cases.forEach { (step, expected) -> assertEquals(step.toString(), expected, maneuverOf(step)) }
    }
}
