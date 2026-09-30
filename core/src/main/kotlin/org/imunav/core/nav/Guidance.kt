package org.imunav.core.nav

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import java.util.Locale
import kotlin.math.roundToInt

/** How navigation continues when no trusted GPS fix is available. */
enum class NavigationMethod {
    /** Advance from speed, time, motion sensors and route knowledge without cell corrections. */
    DEAD_RECKONING,

    /** Follow route-projected fixes computed from the offline cell-tower database. */
    CELL_TOWERS,

    /** Dead reckon continuously while cell/network fixes constrain accumulated drift. */
    HYBRID,
}

/** Where the current position estimate comes from ([label] is shown in the UI and logs). */
enum class PositionSource(val label: String) {
    NONE("—"),

    /** A GOOD GPS fix. */
    GPS("GPS"),

    /** A SUSPECT GPS fix that was consistent with the dead-reckoned position. */
    GPS_SUSPECT("GPS?"),

    /** Dead reckoning (no GPS): speed × time along the route. */
    DR("DR"),

    /** Dead reckoning with fresh network/cell fixes keeping it in check. */
    DR_NET("DR+NET"),

    /** Dead reckoning, and the sensors say the car is standing still. */
    DR_STOPPED("DR⏸"),

    /** A coarse fix computed locally from visible cell towers. */
    CELL("CELL"),
    ;

    val isGps: Boolean get() = this == GPS || this == GPS_SUSPECT
}

/**
 * Everything the UI shows during navigation. The engine publishes a new copy every tick;
 * it is immutable, so the UI can read it from any thread.
 */
data class GuidanceState(
    /** Navigation is running. */
    val active: Boolean = false,
    val route: Route? = null,
    /** Distance travelled along the route, m. */
    val s: Double = 0.0,
    /** The marker's position on the map. */
    val position: GeoPoint? = null,
    /** Road direction at the marker (for rotating the map). */
    val bearingDeg: Float = 0f,
    /** The upcoming maneuver, its index in the route's steps and the distance to it. */
    val nextStep: Step? = null,
    val nextStepIndex: Int = -1,
    val distToNextM: Double = 0.0,
    /** The maneuver after the next one ("then turn left"). */
    val thenStep: Step? = null,
    /** Distance and estimated time to the destination. */
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val speedKmh: Float = 0f,
    /** Car or on foot. */
    val travelMode: TravelMode = TravelMode.CAR,
    val speedLimitKmh: Int? = null,
    /** GPS says we left the route (and how far from it we are). */
    val offRoute: Boolean = false,
    val offRouteM: Double = 0.0,
    val arrived: Boolean = false,
    val source: PositionSource = PositionSource.NONE,
    /** A new route is being computed. */
    val rerouting: Boolean = false,
    val destination: GeoPoint? = null,
    /** Seconds since the last usable GPS fix. */
    val blindS: Int = 0,
    /** How far off the marker may be, metres (drawn as the blue circle). */
    val uncertaintyM: Double = 0.0,
    /** GPS just came back after jamming; suspicious fixes are accepted more generously for a while. */
    val jamRecovering: Boolean = false,
    /** A "you seem to have left the route" countdown is running. */
    val blindDeviation: Boolean = false,
    val blindDeviationSecLeft: Int = 0,
)

/**
 * Moments the driver should notice without looking at the screen. The app turns them into
 * vibration patterns (see the app's `Haptics`); they come together with the matching voice phrase.
 */
enum class NavAlert {
    /** A maneuver is close (150 m by car, 50 m on foot). */
    TURN_SOON,

    /** The maneuver is right here (40 m by car, 15 m on foot). */
    TURN_NOW,

    /** GPS says we left the route, or the sensors suggest a missed turn / U-turn without GPS. */
    OFF_ROUTE,

    /** A new route was installed. */
    REROUTED,

    /** GPS became unusable (jamming/spoofing); navigation continues without it. */
    GPS_LOST,

    /** Trusted GPS is back. */
    GPS_RESTORED,

    /** The destination is reached. */
    ARRIVED,
}

/** How the engine talks to the app. All methods have empty defaults, so implement only what you need. */
interface NavListener {
    /** Speak [text]; [urgent] phrases should interrupt whatever is being said. */
    fun onSay(text: String, urgent: Boolean) {}

    /** Something happened that deserves a vibration (called right before the matching [onSay]). */
    fun onAlert(alert: NavAlert) {}

    /** A line for the trip log (short `key=value` style, useful for debugging and replay). */
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

/** Russian spoken navigation phrases used when the UI language is Russian. */
object RussianPhrases : Phrases {
    private fun distance(m: Double): String =
        if (m >= 1000) "через ${String.format(Locale.US, "%.1f", m / 1000).replace('.', ',')} км" else "через ${((m / 10).roundToInt() * 10)} метров"

    private fun action(step: Step): String {
        val direction = when (step.modifier) {
            "left" -> "налево"
            "right" -> "направо"
            "slight left" -> "плавно налево"
            "slight right" -> "плавно направо"
            "sharp left" -> "резко налево"
            "sharp right" -> "резко направо"
            "uturn" -> "разворот"
            else -> "прямо"
        }
        val onto = if (step.name.isNotBlank()) " на ${step.name}" else ""
        return when (step.type) {
            "roundabout", "rotary" -> "на круговом движении выберите ${step.roundaboutExit?.let { "$it-й съезд" } ?: "съезд"}$onto"
            "fork" -> "держитесь $direction$onto"
            "off ramp" -> "съезд $direction$onto"
            "on ramp", "merge" -> "выезд $direction$onto"
            "arrive" -> "пункт назначения"
            "new name", "continue" -> if (step.modifier == null || step.modifier == "straight") "продолжайте прямо$onto" else "держитесь $direction$onto"
            else -> if (step.modifier == "uturn") "выполните разворот$onto" else "поверните $direction$onto"
        }
    }

    override fun maneuver(step: Step, distanceM: Double?): String =
        if (distanceM == null) action(step).replaceFirstChar { it.uppercase() } else "${distance(distanceM).replaceFirstChar { it.uppercase() }} ${action(step)}"

    override fun arrived() = "Вы прибыли в пункт назначения"
    override fun gpsLost() = "Сигнал GPS потерян. Позиция рассчитывается"
    override fun gpsRestored() = "GPS восстановлен"
    override fun rerouted() = "Маршрут перестроен"
    override fun offRouteRerouting() = "Вы сошли с маршрута. Перестраиваю маршрут"
    override fun offRouteAsk() = "Вы сошли с маршрута. Нажмите «Перестроить», чтобы проложить новый"
    override fun blindUturn(seconds: Int) = "Похоже, вы развернулись. Автоперестроение через $seconds секунд"
    override fun blindOffRoute(seconds: Int) = "Похоже, вы сошли с маршрута. Автоперестроение через $seconds секунд"
    override fun blindMissedTurn(seconds: Int) = "Похоже, вы пропустили поворот. Автоперестроение через $seconds секунд"
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
