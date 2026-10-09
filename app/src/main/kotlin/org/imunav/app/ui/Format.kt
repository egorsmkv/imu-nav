package org.imunav.app.ui

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.ui.graphics.vector.ImageVector
import org.imunav.app.R
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Step
import kotlin.math.roundToInt

/** Maneuver kinds the UI distinguishes (icon + text). */
enum class Maneuver(val icon: ImageVector, val text: Int) {
    LEFT(Icons.Filled.TurnLeft, R.string.mv_turn_left),
    RIGHT(Icons.Filled.TurnRight, R.string.mv_turn_right),
    SLIGHT_LEFT(Icons.Filled.TurnSlightLeft, R.string.mv_slight_left),
    SLIGHT_RIGHT(Icons.Filled.TurnSlightRight, R.string.mv_slight_right),
    SHARP_LEFT(Icons.Filled.TurnSharpLeft, R.string.mv_sharp_left),
    SHARP_RIGHT(Icons.Filled.TurnSharpRight, R.string.mv_sharp_right),
    UTURN(Icons.Filled.UTurnLeft, R.string.mv_uturn),
    STRAIGHT(Icons.Filled.Straight, R.string.mv_straight),
    ROUNDABOUT(Icons.Filled.RoundaboutRight, R.string.mv_roundabout),
    KEEP_LEFT(Icons.Filled.ForkLeft, R.string.mv_keep_left),
    KEEP_RIGHT(Icons.Filled.ForkRight, R.string.mv_keep_right),
    MERGE(Icons.Filled.Merge, R.string.mv_merge),
    RAMP_LEFT(Icons.Filled.RampLeft, R.string.mv_ramp_left),
    RAMP_RIGHT(Icons.Filled.RampRight, R.string.mv_ramp_right),
    ARRIVE(Icons.Filled.Flag, R.string.mv_arrive),
}

/** Map an OSRM step (type + modifier) to what the driver should do. */
fun maneuverOf(step: Step): Maneuver {
    val mod = step.modifier.orEmpty()
    val left = "left" in mod
    val right = "right" in mod
    return when (step.type) {
        "arrive" -> Maneuver.ARRIVE

        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> Maneuver.ROUNDABOUT

        "fork" -> if (left) Maneuver.KEEP_LEFT else Maneuver.KEEP_RIGHT

        "off ramp", "on ramp" -> when {
            left -> Maneuver.RAMP_LEFT
            right -> Maneuver.RAMP_RIGHT
            else -> Maneuver.STRAIGHT
        }

        "merge" -> Maneuver.MERGE

        else -> when (mod) {
            "uturn" -> Maneuver.UTURN
            "left" -> Maneuver.LEFT
            "right" -> Maneuver.RIGHT
            "slight left" -> Maneuver.SLIGHT_LEFT
            "slight right" -> Maneuver.SLIGHT_RIGHT
            "sharp left" -> Maneuver.SHARP_LEFT
            "sharp right" -> Maneuver.SHARP_RIGHT
            else -> Maneuver.STRAIGHT
        }
    }
}

/** Instruction without the street name, e.g. "Turn left" / "At the roundabout, take exit 2". */
fun maneuverText(res: Resources, step: Step): String {
    val m = maneuverOf(step)
    val exit = step.roundaboutExit
    return if (m == Maneuver.ROUNDABOUT && exit != null) res.getString(R.string.mv_roundabout_exit, exit) else res.getString(m.text)
}

/** Full one-line instruction including the street, for notifications. */
fun instructionLine(res: Resources, step: Step): String {
    val base = maneuverText(res, step)
    return if (step.name.isNotBlank() && step.type != "arrive") "$base ${res.getString(R.string.mv_onto, step.name)}" else base
}

/** 40 m, 350 m, 1.2 km, 14 km — rounded the way drivers read distances. */
fun formatDistance(res: Resources, meters: Double): String = when {
    meters < 100 -> res.getString(R.string.unit_m, ((meters / 5).roundToInt() * 5).coerceAtLeast(0))
    meters < 1000 -> res.getString(R.string.unit_m, (meters / 10).roundToInt() * 10)
    meters < 10_000 -> res.getString(R.string.unit_km, meters / 1000)
    else -> res.getString(R.string.unit_km, (meters / 1000).roundToInt().toDouble()).replace(TRAILING_ZERO, " ")
}

/** "14.0 km" → "14 km" (compiled once: regex compilation is expensive and this runs for every list row). */
private val TRAILING_ZERO = Regex("[.,]0 ")

/** "25 min" / "1 h 20 min". */
fun formatDuration(res: Resources, seconds: Double): String {
    val min = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (min < 60) res.getString(R.string.unit_min, min) else res.getString(R.string.unit_h_min, min / 60, min % 60)
}

/** "± 30 m". */
fun formatAccuracy(res: Resources, meters: Double): String = res.getString(R.string.accuracy_pm, formatDistance(res, meters))

/** Localized name of a position source for the status pill. */
fun sourceLabel(res: Resources, source: PositionSource): String = res.getString(
    when (source) {
        PositionSource.NONE -> R.string.src_none
        PositionSource.GPS -> R.string.src_gps
        PositionSource.GPS_SUSPECT -> R.string.src_gps_suspect
        PositionSource.DR -> R.string.src_dr
        PositionSource.DR_NET -> R.string.src_dr_net
        PositionSource.DR_OBD -> R.string.src_dr_obd
        PositionSource.DR_STOPPED -> R.string.src_stopped
        PositionSource.CELL -> R.string.src_cells
    },
)
