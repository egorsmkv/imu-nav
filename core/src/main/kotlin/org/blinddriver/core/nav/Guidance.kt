package org.blinddriver.core.nav

import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.Step
import java.util.Locale
import kotlin.math.roundToInt

/** Where the current position estimate comes from. */
enum class PositionSource(val label: String) {
    NONE("—"),
    GPS("GPS"),

    /** A SUSPECT GPS fix that was consistent with the dead-reckoned position. */
    GPS_SUSPECT("GPS?"),
    DR("DR"),
    DR_NET("DR+NET"),
    DR_STOPPED("DR⏸"),
    ;

    val isGps: Boolean get() = this == GPS || this == GPS_SUSPECT
}

data class GuidanceState(
    val active: Boolean = false,
    val route: Route? = null,
    /** Distance travelled along the route, m. */
    val s: Double = 0.0,
    val position: GeoPoint? = null,
    val bearingDeg: Float = 0f,
    val nextStep: Step? = null,
    val nextStepIndex: Int = -1,
    val distToNextM: Double = 0.0,
    val thenStep: Step? = null,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val speedKmh: Float = 0f,
    val speedLimitKmh: Int? = null,
    val offRoute: Boolean = false,
    val offRouteM: Double = 0.0,
    val arrived: Boolean = false,
    val source: PositionSource = PositionSource.NONE,
    val rerouting: Boolean = false,
    val destination: GeoPoint? = null,
    /** Seconds since the last usable GPS fix. */
    val blindS: Int = 0,
    val uncertaintyM: Double = 0.0,
    val jamRecovering: Boolean = false,
    /** A "you seem to have left the route" countdown is running. */
    val blindDeviation: Boolean = false,
    val blindDeviationSecLeft: Int = 0,
)

interface NavListener {
    fun onSay(text: String, urgent: Boolean) {}
    fun onLog(message: String) {}

    /**
     * The engine wants a new route from [from]. Compute it and call [NavigationEngine.setRoute],
     * or [NavigationEngine.rerouteFailed].
     */
    fun onRerouteRequested(from: GeoPoint, destination: GeoPoint, via: List<GeoPoint>, auto: Boolean) {}
}

/** Localised voice / UI phrases. */
interface Phrases {
    fun maneuver(step: Step, distanceM: Double?): String
    fun arrived(): String
    fun gpsLost(): String
    fun gpsRestored(): String
    fun rerouted(): String
    fun offRouteRerouting(): String
    fun offRouteAsk(): String
    fun blindUturn(seconds: Int): String
    fun blindOffRoute(seconds: Int): String
    fun blindMissedTurn(seconds: Int): String
}

object UkrainianPhrases : Phrases {
    private fun distance(m: Double): String =
        if (m >= 1000) "через ${String.format(Locale.US, "%.1f", m / 1000).replace('.', ',')} км" else "через ${((m / 10).roundToInt() * 10)} метрів"

    private fun action(step: Step): String {
        val dir = when (step.modifier) {
            "left" -> "ліворуч"
            "right" -> "праворуч"
            "slight left" -> "плавно ліворуч"
            "slight right" -> "плавно праворуч"
            "sharp left" -> "різко ліворуч"
            "sharp right" -> "різко праворуч"
            "uturn" -> "розворот"
            else -> "прямо"
        }
        val onto = if (step.name.isNotBlank()) " на ${step.name}" else ""
        return when (step.type) {
            "roundabout", "rotary" -> "на кільці ${step.roundaboutExit?.let { "$it-й з'їзд" } ?: "з'їзд"}$onto"
            "fork" -> "тримайтеся $dir$onto"
            "off ramp" -> "з'їзд $dir$onto"
            "on ramp", "merge" -> "виїзд $dir$onto"
            "arrive" -> "пункт призначення"
            "new name", "continue" -> if (step.modifier == null || step.modifier == "straight") "прямо$onto" else "тримайтеся $dir$onto"
            else -> if (step.modifier == "uturn") "розворот$onto" else "поверніть $dir$onto"
        }
    }

    override fun maneuver(step: Step, distanceM: Double?): String =
        if (distanceM == null) action(step).replaceFirstChar { it.uppercase() } else "${distance(distanceM).replaceFirstChar { it.uppercase() }} ${action(step)}"

    override fun arrived() = "Ви прибули до пункту призначення"
    override fun gpsLost() = "Сигнал GPS втрачено. Позиція розрахункова"
    override fun gpsRestored() = "GPS відновлено"
    override fun rerouted() = "Маршрут перебудовано"
    override fun offRouteRerouting() = "Ви зійшли з маршруту. Перебудовую маршрут"
    override fun offRouteAsk() = "Ви зійшли з маршруту. Натисніть «Перебудувати», щоб прокласти новий"
    override fun blindUturn(seconds: Int) = "Схоже, ви розвернулися. Автоперебудова через $seconds секунд"
    override fun blindOffRoute(seconds: Int) = "Схоже, ви зійшли з маршруту. Автоперебудова через $seconds секунд"
    override fun blindMissedTurn(seconds: Int) = "Схоже, ви проїхали поворот. Автоперебудова через $seconds секунд"
}

object EnglishPhrases : Phrases {
    private fun distance(m: Double): String = if (m >= 1000) "In ${String.format(Locale.US, "%.1f", m / 1000)} kilometres" else "In ${((m / 10).roundToInt() * 10)} metres"

    private fun action(step: Step): String {
        val dir = step.modifier ?: "straight"
        val onto = if (step.name.isNotBlank()) " onto ${step.name}" else ""
        return when (step.type) {
            "roundabout", "rotary" -> "at the roundabout take ${step.roundaboutExit?.let { "exit $it" } ?: "the exit"}$onto"
            "fork" -> "keep $dir$onto"
            "off ramp" -> "take the ramp $dir$onto"
            "on ramp", "merge" -> "merge $dir$onto"
            "arrive" -> "you will arrive"
            "new name", "continue" -> if (dir == "straight") "continue$onto" else "keep $dir$onto"
            else -> if (dir == "uturn") "make a U-turn$onto" else "turn $dir$onto"
        }
    }

    override fun maneuver(step: Step, distanceM: Double?): String =
        if (distanceM == null) action(step).replaceFirstChar { it.uppercase() } else "${distance(distanceM)}, ${action(step)}"

    override fun arrived() = "You have arrived at your destination"
    override fun gpsLost() = "GPS signal lost. Position is estimated"
    override fun gpsRestored() = "GPS restored"
    override fun rerouted() = "Route recalculated"
    override fun offRouteRerouting() = "You left the route. Recalculating"
    override fun offRouteAsk() = "You left the route. Tap Reroute to plan a new one"
    override fun blindUturn(seconds: Int) = "Looks like you turned around. Rerouting in $seconds seconds"
    override fun blindOffRoute(seconds: Int) = "Looks like you left the route. Rerouting in $seconds seconds"
    override fun blindMissedTurn(seconds: Int) = "Looks like you missed the turn. Rerouting in $seconds seconds"
}
