package org.imunav.core.nav

import org.imunav.core.Tuning
import org.imunav.core.imu.MotionDetector
import org.imunav.core.route.Route
import org.imunav.core.route.RouteCursor
import kotlin.math.abs
import kotlin.math.max

/** Owns turn hold and snap state so each route transition resets it as a unit. */
internal class TurnProgress(
    private val motion: MotionDetector,
    private val net: NetworkPositionTracker,
    private val settings: () -> Tuning,
    private val phrases: () -> Phrases,
    private val log: (String) -> Unit,
    private val offerDeviation: (Long, String, String) -> Unit,
    private val onSnap: () -> Unit,
) {
    private val consumedSteps = HashSet<Int>()
    var holdS = Double.MAX_VALUE
        private set
    private var holdStep = -1
    private var holdTravel = 0.0
    private var holdMovingMs = 0L
    private var holdAccStep = -1
    private var missedTurnStep = -1

    /** Discard all state associated with the previous route. */
    fun reset() {
        consumedSteps.clear()
        clearHold()
        motion.resetHoldAccumulator()
        holdAccStep = -1
        missedTurnStep = -1
    }

    /** Restored navigation must not match turns already behind the marker. */
    fun markPassed(route: Route, s: Double) {
        route.steps.indices.filter { route.stepS(it) < s - 1.0 }.forEach { consumedSteps += it }
    }

    /** Last confirmed maneuver provides a lower bound for network corrections. */
    fun lastConfirmedTurnS(car: RouteCursor): Double? = consumedSteps.map { car.route.stepS(it) + 20.0 }.filter { it <= car.s }.maxOrNull()

    fun nextHoldableTurn(car: RouteCursor, config: Tuning): Triple<Int, Double, Double>? {
        val route = car.route
        for (i in route.steps.indices) {
            if (i in consumedSteps || route.steps[i].isDepartOrArrive) continue
            val turnS = route.stepS(i)
            if (turnS < car.s - 1.0) continue // already behind us
            val turn = route.turnAngleAt(turnS)
            if (abs(turn) >= config.turnMinDeg) return Triple(i, turnS, turn)
        }
        return null
    }

    /**
     * Move forward by speedMps·dt, but park the marker 5 m before the next real turn until the turn is
     * confirmed (gyro or network), found to be missed, or a timeout expires.
     */
    fun advance(car: RouteCursor, speedMps: Double, dt: Double, nowMs: Long) {
        val config = settings()
        val ds = speedMps * dt
        if (!config.turnHoldEnabled) {
            clearHold()
            car.advance(ds)
            return
        }
        val target = car.s + ds
        val next = nextHoldableTurn(car, config)
        val holdAt = next?.let { max(it.second - 5.0, 0.0) } ?: Double.MAX_VALUE
        if (next == null || target <= holdAt) {
            clearHold()
            car.advance(ds)
            return
        }
        val (step, stepS, stepTurn) = next
        if (holdStep != step) {
            holdStep = step
            holdTravel = 0.0
            holdMovingMs = 0L
            if (holdAccStep != step || !motion.holdAccumulating) {
                motion.startHoldAccumulator(nowMs)
                holdAccStep = step
            }
            log("turn_hold step=$step s=${holdAt.toInt()} route_turn=${stepTurn.toInt()}")
        }
        holdS = holdAt
        holdTravel += ds
        if (!motion.stopped) holdMovingMs += (dt * 1000).toLong()
        car.moveTo(max(car.s, holdAt))

        // Network says we are well past the turn.
        if (holdMovingMs >= 8000 && net.recent.size >= 2) {
            val lastTwo = net.recent.takeLast(2)
            if (lastTwo.all { nowMs - it.elapsedMs <= 12_000 && it.accM <= 100.0 && it.offsetM <= 130.0 && it.s - 100.0 > stepS + 20.0 }) {
                snapPastTurn(car, step, max(stepS + 20.0, car.s), nowMs, "net")
                return
            }
        }

        val holdYaw = motion.holdYawDeg
        val gyroAgrees = holdYaw * stepTurn > 0 && abs(holdYaw) >= 0.3 * abs(stepTurn)
        if (config.missedTurnEnabled && config.blindDeviationEnabled && missedTurnStep != step && holdTravel >= config.missedTurnM && !gyroAgrees) {
            missedTurnStep = step
            offerDeviation(
                nowMs,
                "blind_deviation_missed_turn step=$step travel=${holdTravel.toInt()} gyro=${holdYaw.toInt()} delay_s=${config.blindDeviationDelayS}",
                phrases().blindMissedTurn(config.blindDeviationDelayS),
            )
        }

        releaseHoldOnTimeout(car, step, stepTurn, gyroAgrees, target, nowMs)
    }

    /** Give up holding after [Tuning.turnHoldMaxS] of driving (15 s more if the gyro shows a turn starting). */
    private fun releaseHoldOnTimeout(car: RouteCursor, step: Int, stepTurn: Double, gyroAgrees: Boolean, target: Double, nowMs: Long) {
        val limitMs = settings().turnHoldMaxS * 1000L
        if (holdMovingMs <= limitMs) return
        val recentYaw = motion.integratedYaw(nowMs, 3000)
        val partial = (recentYaw * stepTurn > 0 && abs(recentYaw) >= 10.0) || gyroAgrees
        if (partial && holdMovingMs <= limitMs + 15_000) return
        log("turn_hold_release step=$step reason=timeout gyro=${motion.holdYawDeg.toInt()}")
        consumedSteps += step
        clearHold()
        motion.resetHoldAccumulator()
        holdAccStep = -1
        car.moveTo(target)
    }

    /** The held turn is confirmed once the gyro has turned the same way by enough. */
    fun confirmHeldTurn(car: RouteCursor, nowMs: Long) {
        val step = holdStep
        if (step < 0 || step in consumedSteps || holdAccStep != step) return
        val route = car.route
        val turnS = route.stepS(step)
        val turn = route.turnAngleAt(turnS)
        val yaw = motion.holdYawDeg
        // Same direction (signs agree) and at least 60 % of the route's turn angle.
        val sameDirection = yaw * turn > 0
        if (!sameDirection || abs(yaw) < max(settings().turnMinDeg, abs(turn) * 0.6)) return
        snapPastTurn(car, step, turnS + 20.0, nowMs, "hold")
    }

    /** Any clear gyro turn is matched against route turns near the marker (turn-signature map matching). */
    fun matchGyroTurn(car: RouteCursor, nowMs: Long) {
        val config = settings()
        val yaw = motion.integratedYaw(nowMs, config.turnWindowMs)
        if (abs(yaw) < config.turnMinDeg) return
        val route = car.route
        // The route turn (near the marker) whose angle is closest to what the gyro measured.
        var best = -1
        var bestError = config.turnTolDeg
        for (i in route.steps.indices) {
            if (i in consumedSteps || route.steps[i].isDepartOrArrive) continue
            val turnS = route.stepS(i)
            if (turnS < car.s - config.turnBehindM || turnS > car.s + config.turnAheadM) continue
            val turn = route.turnAngleAt(turnS)
            if (abs(turn) < 0.8 * config.turnMinDeg) continue
            val error = abs(turn - yaw)
            if (error < bestError) {
                bestError = error
                best = i
            }
        }
        if (best < 0) return
        snapPastTurn(car, best, route.stepS(best) + 20.0, nowMs, "gyro", yaw)
    }

    /** A turn is confirmed: jump the marker to just after it and reset the turn detectors. */
    private fun snapPastTurn(car: RouteCursor, step: Int, toS: Double, nowMs: Long, src: String, yaw: Double = motion.holdYawDeg) {
        consumedSteps += step
        log("turn_snap step=$step gyro=${yaw.toInt()} from_s=${car.s.toInt()} to_s=${toS.toInt()} src=$src")
        car.moveTo(toS)
        onSnap()
        clearHold()
        motion.resetHoldAccumulator()
        holdAccStep = -1
        motion.turnResetMs = nowMs
    }

    private fun clearHold() {
        holdStep = -1
        holdS = Double.MAX_VALUE
    }
}
