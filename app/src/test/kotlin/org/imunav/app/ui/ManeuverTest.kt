package org.imunav.app.ui

import org.imunav.core.route.ManeuverModifier
import org.imunav.core.route.ManeuverType
import org.imunav.core.route.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** The map and car cues should interpret OSRM's turn vocabulary consistently. */
class ManeuverTest {
    @Test
    fun mapsJunctionsAndRampsToTheSideTheDriverMustTake() {
        val cases = listOf(
            Step(ManeuverType.FORK, ManeuverModifier.SLIGHT_LEFT) to Maneuver.KEEP_LEFT,
            Step(ManeuverType.FORK, ManeuverModifier.SLIGHT_RIGHT) to Maneuver.KEEP_RIGHT,
            Step(ManeuverType.ON_RAMP, ManeuverModifier.LEFT) to Maneuver.RAMP_LEFT,
            Step(ManeuverType.OFF_RAMP, ManeuverModifier.RIGHT) to Maneuver.RAMP_RIGHT,
            Step(ManeuverType.ON_RAMP) to Maneuver.STRAIGHT,
            Step(ManeuverType.MERGE, ManeuverModifier.RIGHT) to Maneuver.MERGE,
        )
        cases.forEach { (step, expected) -> assertEquals(step.toString(), expected, maneuverOf(step)) }
    }

    @Test
    fun mapsRoundaboutsArrivalsAndTurnModifiers() {
        val cases = listOf(
            Step(ManeuverType.ROUNDABOUT) to Maneuver.ROUNDABOUT,
            Step(ManeuverType.EXIT_ROTARY) to Maneuver.ROUNDABOUT,
            Step(ManeuverType.ARRIVE) to Maneuver.ARRIVE,
            Step(ManeuverType.TURN, ManeuverModifier.UTURN) to Maneuver.UTURN,
            Step(ManeuverType.TURN, ManeuverModifier.SHARP_LEFT) to Maneuver.SHARP_LEFT,
            Step(ManeuverType.TURN, ManeuverModifier.SHARP_RIGHT) to Maneuver.SHARP_RIGHT,
            Step(ManeuverType.TURN, ManeuverModifier.SLIGHT_LEFT) to Maneuver.SLIGHT_LEFT,
            Step(ManeuverType.TURN, ManeuverModifier.SLIGHT_RIGHT) to Maneuver.SLIGHT_RIGHT,
            Step(ManeuverType.TURN, ManeuverModifier.LEFT) to Maneuver.LEFT,
            Step(ManeuverType.TURN, ManeuverModifier.RIGHT) to Maneuver.RIGHT,
            Step(ManeuverType.CONTINUE) to Maneuver.STRAIGHT,
        )
        cases.forEach { (step, expected) -> assertEquals(step.toString(), expected, maneuverOf(step)) }
    }
}
