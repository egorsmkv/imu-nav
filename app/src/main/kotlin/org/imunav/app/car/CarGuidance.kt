package org.imunav.app.car

import android.content.Context
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import org.imunav.app.R
import org.imunav.app.ui.instructionLine
import org.imunav.core.nav.GuidanceState
import org.imunav.core.route.ManeuverModifier
import org.imunav.core.route.ManeuverType
import java.time.ZonedDateTime
import org.imunav.core.route.Step as RouteStep

/** Adapts one immutable engine snapshot for both the car template and the host's navigation manager. */
class CarGuidance(private val context: Context) {
    fun distance(metres: Double): Distance = Distance.create(metres.coerceAtLeast(0.0), Distance.UNIT_METERS)

    fun estimate(metres: Double, seconds: Double, status: String): TravelEstimate =
        TravelEstimate.Builder(distance(metres), ZonedDateTime.now().plusSeconds(seconds.toLong().coerceAtLeast(0)))
            .setRemainingTimeSeconds(seconds.toLong().coerceAtLeast(0)).setTripText(CarText.create(status)).build()

    /** Arrival and rerouting must not leave an old turn on the head unit. */
    fun routing(state: GuidanceState): RoutingInfo {
        if (state.rerouting) return RoutingInfo.Builder().setLoading(true).build()
        val builder = RoutingInfo.Builder().setCurrentStep(step(state.nextStep, state.arrived), distance(state.distToNextM))
        if (!state.arrived) state.thenStep?.let { builder.setNextStep(step(it, false)) }
        return builder.build()
    }

    fun trip(state: GuidanceState, destination: String, status: String): Trip {
        val builder = Trip.Builder().addDestination(
            Destination.Builder().setName(destination).build(),
            estimate(state.remainingM, state.remainingS, status),
        )
        if (state.rerouting) {
            builder.setLoading(true)
        } else {
            builder.addStep(
                step(state.nextStep, state.arrived),
                estimate(state.distToNextM, state.nextStep?.durationS ?: 0.0, status),
            )
        }
        return builder.build()
    }

    private fun step(step: RouteStep?, arrived: Boolean): Step {
        val cue = when {
            arrived -> context.getString(R.string.arrived)
            step != null -> instructionLine(context.resources, step)
            else -> context.getString(R.string.app_name)
        }
        return Step.Builder(cue).setManeuver(maneuver(step, arrived)).setRoad(step?.name.orEmpty()).build()
    }

    private fun maneuver(step: RouteStep?, arrived: Boolean): Maneuver {
        val type = when {
            arrived || step?.type == ManeuverType.ARRIVE -> Maneuver.TYPE_DESTINATION

            step?.type == ManeuverType.DEPART -> Maneuver.TYPE_DEPART

            step?.type in listOf(ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN) -> {
                if ((step?.roundaboutExit ?: 0) > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            }

            step?.type in listOf(ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY) -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW

            step?.type == ManeuverType.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED

            step?.type == ManeuverType.FORK -> when (step.modifier) {
                ManeuverModifier.LEFT, ManeuverModifier.SLIGHT_LEFT -> Maneuver.TYPE_FORK_LEFT
                ManeuverModifier.RIGHT, ManeuverModifier.SLIGHT_RIGHT -> Maneuver.TYPE_FORK_RIGHT
                else -> Maneuver.TYPE_UNKNOWN
            }

            else -> when (step?.modifier) {
                ManeuverModifier.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
                ManeuverModifier.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                ManeuverModifier.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
                ManeuverModifier.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
                ManeuverModifier.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
                ManeuverModifier.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
                ManeuverModifier.UTURN -> Maneuver.TYPE_U_TURN_LEFT
                else -> Maneuver.TYPE_STRAIGHT
            }
        }
        return Maneuver.Builder(type).apply {
            if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) setRoundaboutExitNumber((step?.roundaboutExit ?: 1).coerceAtLeast(1))
        }.build()
    }
}
