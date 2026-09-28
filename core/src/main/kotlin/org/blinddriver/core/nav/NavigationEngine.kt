package org.blinddriver.core.nav

import org.blinddriver.core.Tuning
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.gnss.GpsState
import org.blinddriver.core.gnss.JudgedFix
import org.blinddriver.core.gnss.PositioningSnapshot
import org.blinddriver.core.gnss.RawFix
import org.blinddriver.core.gnss.TrustLevel
import org.blinddriver.core.imu.ImuSample
import org.blinddriver.core.imu.MotionDetector
import org.blinddriver.core.route.Hazard
import org.blinddriver.core.route.HazardKind
import org.blinddriver.core.route.Projection
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.RouteCursor
import org.blinddriver.core.speed.MAX_SPEED_MPS
import org.blinddriver.core.speed.RouteSpeedPrior
import org.blinddriver.core.speed.SpeedEstimate
import org.blinddriver.core.speed.SpeedFusion
import org.blinddriver.core.speed.SpeedPlan
import org.blinddriver.core.speed.SpeedProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Route-constrained dead-reckoning navigator.
 *
 * The only state is `s`, the distance travelled along the planned route. Call [tick] every
 * [TICK_MS] with a fresh [PositioningSnapshot] and feed every IMU sample to [onImu].
 *
 * Per tick:
 *  1. network fixes are gated, projected to the route and used for speed and drift bounds;
 *  2. a usable GPS fix (GOOD, or SUSPECT but consistent) moves the marker directly;
 *  3. otherwise `s += v·dt·motionFactor`, where v fuses GPS / route prior / network speed;
 *  4. landmark corrections snap `s`: turn-hold + gyro confirmation, gyro turn matching,
 *     compass heading, stop at traffic signal, network band / catch-up / pull-back;
 *  5. deviations (U-turn, missed turn, network off-route, GPS off-route) trigger reroutes.
 */
class NavigationEngine(
    private val tuning: () -> Tuning = { Tuning.DEFAULT },
    private val speedProfile: SpeedProfile = SpeedProfile(),
    /** Spoken phrase set; may be switched at runtime (language change). */
    var phrases: Phrases = UkrainianPhrases,
    private val listener: NavListener,
    /** Extra traffic-calming points (speed bumps) to consider on every route. */
    private val trafficCalming: List<GeoPoint> = emptyList(),
) {
    val motion = MotionDetector(tuning).also { it.log = ::log }

    var state = GuidanceState()
        private set

    /** Reroute automatically when GPS says we left the route; otherwise only announce it. */
    var autoReroute = true

    /** Debug: ignore GPS entirely, as if it were jammed. */
    var simulateGpsLoss = false

    private var cursor: RouteCursor? = null
    private var destination: GeoPoint? = null
    private var waypoints: List<GeoPoint> = emptyList()
    private var waypointS: List<Pair<GeoPoint, Double>> = emptyList()
    private var hazards: List<Hazard> = emptyList()
    private var rerouting = false
    private var navStartMs = 0L
    private var lastTickMs = -1L
    private var source = PositionSource.NONE

    // GPS
    private var lastGpsProcessedMs = -1L
    private var lastGpsUseMs = 0L
    private var lastGpsSpeed: Double? = null
    private var trustedPoint: GeoPoint? = null
    private val smoother = MarkerSmoother()
    private val recovery = JamRecovery()
    private var offRouteSinceMs = -1L
    private var offRouteFastSinceMs = -1L
    private var offRouteDeclared = false
    private var offRouteM = 0.0

    // Dead reckoning
    private var currentSpeed = 0.0
    private var drDistance = 0.0

    /** Accuracy of the trip's start position; applies until GPS is first used. */
    private var startAccuracyM = 0.0
    private var catchUp = 0.0
    private var lastCatchupFixMs = -1L
    private var cachedNet: SpeedEstimate? = null
    private var cachedNetAtMs = 0L
    private val motionHistory = ArrayDeque<Pair<Long, Double>>()
    private var netOverridesStop = false
    private var wasStopped = false

    // Network
    private val net = NetworkTracker()
    private var lastNetProcessedMs = -1L
    private var netRejectLogMs = 0L
    private var netDevFastCount = 0
    private var netDevFastKey: String? = null
    private var netDevCount = 0
    private var lastNetBackMs = -30_000L

    // Turns
    private val consumedSteps = HashSet<Int>()
    private var holdStep = -1
    private var holdS = Double.MAX_VALUE
    private var holdTravel = 0.0
    private var holdMovingMs = 0L
    private var holdAccStep = -1
    private var missedTurnStep = -1

    // Compass / signals
    private var compassMismatchSinceMs = 0L
    private var lastCompassSnapMs = -30_000L
    private val usedSignals = HashSet<Int>()

    // Deviation offers
    private val deviation = DeviationOffer()
    private var deviationFrom: GeoPoint? = null

    // Announcements
    private val announced = HashSet<Int>()
    private var gpsLostAnnounced = false
    private var arrivedAnnounced = false

    val route: Route? get() = cursor?.route

    // ------------------------------------------------------------------ lifecycle

    /**
     * @param startAccuracyM accuracy of the position the route was planned from (e.g. a coarse cell
     *   fix). Until the first usable GPS fix, reported uncertainty never drops below it.
     */
    fun start(
        route: Route,
        destination: GeoPoint,
        waypoints: List<GeoPoint> = emptyList(),
        nowMs: Long,
        startAccuracyM: Double = 0.0,
    ) {
        this.startAccuracyM = startAccuracyM.coerceAtLeast(0.0)
        this.destination = destination
        this.waypoints = waypoints
        navStartMs = nowMs
        lastGpsUseMs = 0L
        lastGpsProcessedMs = -1L
        lastGpsSpeed = null
        source = PositionSource.NONE
        gpsLostAnnounced = false
        installRoute(route, nowMs)
        log("nav_start len=${route.length.toInt()}")
    }

    /**
     * Continue a trip restored after the app was killed: jump to [s] on the already started route.
     * Call right after [start] (with the restored uncertainty as start accuracy).
     */
    fun resumeAt(s: Double) {
        val c = cursor ?: return
        c.moveTo(s)
        // Turns already behind the saved position were driven.
        c.route.steps.indices.filter { c.route.stepS(it) < s - 1.0 }.forEach { consumedSteps += it }
        log("nav_resume s=${s.toInt()}")
    }

    /** Current along-route position (for persisting an active trip). */
    val progressS: Double get() = cursor?.s ?: 0.0

    /** Install a route computed in response to [NavListener.onRerouteRequested]. */
    fun setRoute(route: Route, nowMs: Long) {
        if (cursor == null) return
        if (deviation.pending) log("blind_deviation_drop reason=new_route")
        installRoute(route, nowMs)
        deviation.clear(nowMs, 40_000)
        log("rerouted len=${route.length.toInt()}")
        say(phrases.rerouted(), urgent = false)
    }

    fun rerouteFailed() {
        rerouting = false
        publishFlags()
    }

    fun stop() {
        cursor?.let { log("nav_stop s=${it.s.toInt()}") }
        cursor = null
        destination = null
        state = GuidanceState()
    }

    fun onImu(sample: ImuSample, yawBiasDegS: Double = 0.0) = motion.add(sample, yawBiasDegS)

    /** User accepted the "you left the route" countdown early. */
    fun confirmDeviation(nowMs: Long) {
        if (!deviation.pending) return
        deviation.clear(nowMs, 90_000)
        deviationFrom?.let { requestReroute(it, auto = false) }
        publishFlags()
    }

    fun dismissDeviation(nowMs: Long) {
        if (!deviation.pending) return
        log("blind_deviation_dismissed")
        deviation.clear(nowMs, 90_000)
        publishFlags()
    }

    /** Manual "Reroute" button: from the last trusted GPS point, else the estimated position. */
    fun requestManualReroute() {
        val from = trustedPoint ?: state.position ?: return
        requestReroute(from, auto = false)
    }

    private fun installRoute(route: Route, nowMs: Long) {
        val c = RouteCursor(route)
        cursor = c
        hazards = route.hazards(trafficCalming)
        waypointS = waypoints.map { it to route.project(it, 0.0, 0.0, route.length, 0.0).s }
        consumedSteps.clear()
        usedSignals.clear()
        announced.clear()
        net.reset()
        motionHistory.clear()
        clearHold()
        motion.resetHoldAccumulator()
        holdAccStep = -1
        missedTurnStep = -1
        catchUp = 0.0
        rerouting = false
        arrivedAnnounced = false
        offRouteDeclared = false
        offRouteSinceMs = -1L
        offRouteFastSinceMs = -1L
        smoother.valid = false
        lastTickMs = -1L
        deviation.cooldownUntilMs = max(deviation.cooldownUntilMs, nowMs + 40_000)
        state = GuidanceState(active = true, route = route, destination = destination, position = route.pointAt(0.0).point)
    }

    // ------------------------------------------------------------------ main loop

    fun tick(nowMs: Long, pos: PositioningSnapshot) {
        val c = cursor ?: return
        val dt = if (lastTickMs < 0) 0.0 else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 5.0)
        lastTickMs = nowMs
        recovery.update(pos.jammed, pos.gpsState == GpsState.LOST || simulateGpsLoss, nowMs)

        processNetwork(c, pos.lastNet, nowMs)
        if (lastGpsUseMs == 0L || nowMs - lastGpsUseMs >= 3000) netBack(c, nowMs)

        val gps = if (simulateGpsLoss) null else pos.lastUsableGps
        val usedGps = gps != null && gps.fix.elapsedMs > lastGpsProcessedMs && handleGps(c, gps, nowMs, dt)
        if (!usedGps) {
            if (lastGpsUseMs > 0 && nowMs - lastGpsUseMs < 3000 && source.isGps) {
                if (smoother.valid) c.moveTo(smoother.follow(c.s, nowMs, dt, snap = false))
            } else {
                deadReckon(c, pos, nowMs, dt)
            }
        }
        deviationTick(nowMs)
        publish(c, pos, nowMs)
    }

    // ------------------------------------------------------------------ GPS

    private fun handleGps(c: RouteCursor, judged: JudgedFix, nowMs: Long, dt: Double): Boolean {
        val fix = judged.fix
        lastGpsProcessedMs = fix.elapsedMs
        val route = c.route
        val proj = route.project(fix.point, c.s, 250.0, 2500.0, 120.0)
        val good = judged.verdict.level == TrustLevel.GOOD
        recovery.onFix(good)
        val inRecovery = recovery.active(nowMs)
        val maxJump = if (inRecovery) 600.0 else 300.0
        val accOk = !inRecovery || (fix.accuracyM ?: Float.MAX_VALUE) <= 150f
        val consistent = proj.offsetM < 60.0 && abs(proj.s - c.s) < maxJump && accOk
        if (!good && !consistent) return false

        if (deviation.pending) {
            deviation.clear(nowMs, 90_000)
            log("blind_deviation_gps_return")
        }
        val speed = fix.speedMps?.toDouble()
        if (proj.s >= c.s - 40.0 || proj.offsetM < 25.0) {
            smoother.anchor(proj.s, fix.elapsedMs, speed?.takeIf { it in 0.0..70.0 } ?: 0.0)
            c.moveTo(smoother.follow(c.s, nowMs, dt, snap = !source.isGps))
        }
        drDistance = 0.0
        catchUp = 0.0
        if (speed != null) {
            lastGpsSpeed = speed
            currentSpeed = speed
            if (good) route.maxspeedAtSegment(proj.segment)?.let { speedProfile.learn(speed, it) }
        }
        lastGpsUseMs = fix.elapsedMs
        if (good) trustedPoint = fix.point

        val bearing = fix.bearingDeg
        val courseDiff = if (bearing != null && (speed ?: 0.0) >= 15.0 / 3.6) {
            Geo.absAngleDiff(bearing.toDouble(), route.bearingAt(min(proj.s + 25.0, route.length)))
        } else {
            null
        }
        if (good) checkOffRoute(c, proj, courseDiff, fix, nowMs)
        source = if (good) PositionSource.GPS else PositionSource.GPS_SUSPECT
        return true
    }

    private fun checkOffRoute(c: RouteCursor, proj: Projection, courseDiff: Double?, fix: RawFix, nowMs: Long) {
        val t = tuning()
        offRouteM = proj.offsetM
        if (c.route.length - proj.s < max(t.arriveM, 15.0)) {
            offRouteSinceMs = -1L
            offRouteFastSinceMs = -1L
            offRouteDeclared = false
            return
        }
        val fast = courseDiff != null && proj.offsetM > t.offRouteFastM && courseDiff > t.offRouteFastDeg
        val far = proj.offsetM > t.offRouteM
        if (fast) {
            if (offRouteFastSinceMs < 0) offRouteFastSinceMs = nowMs
            if (nowMs - offRouteFastSinceMs >= t.offRouteFastHoldMs) declareOffRoute(fix, "fast course_diff=${courseDiff?.toInt()}")
        } else {
            offRouteFastSinceMs = -1L
        }
        if (far) {
            if (offRouteSinceMs < 0) offRouteSinceMs = nowMs
            if (nowMs - offRouteSinceMs >= t.offRouteHoldMs) declareOffRoute(fix, "")
        } else {
            offRouteSinceMs = -1L
        }
        if (!fast && !far) offRouteDeclared = false
    }

    private fun declareOffRoute(fix: RawFix, detail: String) {
        if (offRouteDeclared) return
        offRouteDeclared = true
        log("off_route ${offRouteM.toInt()}m $detail".trim())
        if (autoReroute) {
            say(phrases.offRouteRerouting(), urgent = true)
            log("auto_reroute")
            requestReroute(fix.point, auto = true)
        } else {
            say(phrases.offRouteAsk(), urgent = true)
        }
    }

    // ------------------------------------------------------------------ dead reckoning

    private fun deadReckon(c: RouteCursor, pos: PositioningSnapshot, nowMs: Long, dt: Double) {
        val t = tuning()
        val route = c.route
        val sinceGps = nowMs - if (lastGpsUseMs > 0) lastGpsUseMs else navStartMs
        val factor = motion.motionFactor(nowMs)

        var netSpeed = net.speed.estimate(nowMs)
        if (netSpeed != null) {
            cachedNet = netSpeed
            cachedNetAtMs = nowMs
        } else if (factor == 0.0) {
            cachedNet = null
        }
        if (netSpeed == null) {
            val cached = cachedNet
            val age = nowMs - cachedNetAtMs
            if (cached != null && age in 0..90_000) netSpeed = cached.copy(sigmaMps = max(cached.sigmaMps, 0.3) + 0.1 * age / 1000.0)
        }
        if (factor != null) {
            motionHistory.addLast(nowMs to factor)
            while (motionHistory.isNotEmpty() && nowMs - motionHistory.first().first > 90_000) motionHistory.removeFirst()
        }
        // Network speed averages over stops; rescale it to the speed while actually moving.
        if (netSpeed != null && motionHistory.isNotEmpty()) {
            val duty = motionHistory.sumOf { it.second } / motionHistory.size
            if (duty >= 0.25) netSpeed = netSpeed.copy(speedMps = min(netSpeed.speedMps / duty, MAX_SPEED_MPS))
        }

        val base = SpeedFusion.fuse(lastGpsSpeed, sinceGps, RouteSpeedPrior.at(route, c.s, speedProfile), netSpeed)
        val strict = net.speed.strictEstimate(nowMs)
        val override = strict != null && strict.speedMps >= 4.0 && strict.sigmaMps <= 1.5 && strict.spanS >= 45.0 && motion.accMean >= 0.08
        if (override != netOverridesStop && factor == 0.0) log("net_overrides_stop active=$override netv=${((strict?.speedMps ?: 0.0) * 3.6).toInt()}")
        netOverridesStop = override

        val gpsSpeed = lastGpsSpeed
        var v = when {
            factor == 0.0 && override -> base
            factor != null -> factor * base
            gpsSpeed != null && sinceGps < 120_000 -> gpsSpeed
            gpsSpeed != null && sinceGps < 180_000 -> gpsSpeed * (1.0 - (sinceGps - 120_000) / 60_000.0)
            else -> base
        }
        if (t.speedPlan && hazards.isNotEmpty()) SpeedPlan.cap(hazards, c.s, netSpeed == null)?.let { v = min(v, it) }
        currentSpeed = v

        if (v > 0.3) advance(c, v, dt, nowMs)
        confirmHeldTurn(c, nowMs)
        matchGyroTurn(c, nowMs)
        checkBlindUturn(c, nowMs)
        compassSnap(c, pos.compassDeg, v, nowMs)

        val stopped = motion.stopped && !override
        if (t.signalSnap && stopped && !wasStopped) signalSnap(c)
        wasStopped = stopped
        bandCorrection(c, pos.lastNet, nowMs, dt, stopped)

        source = if (stopped) {
            PositionSource.DR_STOPPED
        } else {
            applyCatchUp(c, dt)
            val netFresh = pos.lastNet?.let { nowMs - it.elapsedMs < 30_000 } == true
            if (netFresh) PositionSource.DR_NET else PositionSource.DR
        }
    }

    /**
     * Move forward by v·dt, but park the marker 5 m before the next real turn until the turn is
     * confirmed (gyro or network), found to be missed, or a timeout expires.
     */
    private fun advance(c: RouteCursor, v: Double, dt: Double, nowMs: Long) {
        val t = tuning()
        val ds = v * dt
        drDistance += ds
        if (!t.turnHoldEnabled) {
            clearHold()
            c.advance(ds)
            return
        }
        val route = c.route
        val target = c.s + ds
        var step = -1
        var stepS = 0.0
        var stepTurn = 0.0
        for (i in route.steps.indices) {
            if (i in consumedSteps || route.steps[i].isDepartOrArrive) continue
            val si = route.stepS(i)
            if (si < c.s - 1.0) continue
            val turn = route.turnAngleAt(si)
            if (abs(turn) < t.turnMinDeg) continue
            step = i
            stepS = si
            stepTurn = turn
            break
        }
        val holdAt = if (step >= 0) max(stepS - 5.0, 0.0) else Double.MAX_VALUE
        if (step < 0 || target <= holdAt) {
            clearHold()
            c.advance(ds)
            return
        }
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
        c.moveTo(max(c.s, holdAt))

        // Network says we are well past the turn.
        if (holdMovingMs >= 8000 && net.recent.size >= 2) {
            val lastTwo = net.recent.takeLast(2)
            if (lastTwo.all { nowMs - it.elapsedMs <= 12_000 && it.accM <= 100.0 && it.offsetM <= 130.0 && it.s - 100.0 > stepS + 20.0 }) {
                snapPastTurn(c, step, max(stepS + 20.0, c.s), nowMs, "net")
                return
            }
        }

        val holdYaw = motion.holdYawDeg
        val gyroAgrees = holdYaw * stepTurn > 0 && abs(holdYaw) >= 0.3 * abs(stepTurn)
        if (t.missedTurnEnabled && t.blindDeviationEnabled && missedTurnStep != step && holdTravel >= t.missedTurnM && !gyroAgrees) {
            missedTurnStep = step
            offerDeviation(
                nowMs,
                "blind_deviation_missed_turn step=$step travel=${holdTravel.toInt()} gyro=${holdYaw.toInt()} delay_s=${t.blindDeviationDelayS}",
                phrases.blindMissedTurn(t.blindDeviationDelayS),
            )
        }

        val limitMs = t.turnHoldMaxS * 1000L
        if (holdMovingMs > limitMs) {
            val recentYaw = motion.integratedYaw(nowMs, 3000)
            val partial = (recentYaw * stepTurn > 0 && abs(recentYaw) >= 10.0) || gyroAgrees
            if (!partial || holdMovingMs > limitMs + 15_000) {
                log("turn_hold_release step=$step reason=timeout gyro=${holdYaw.toInt()}")
                consumedSteps += step
                clearHold()
                motion.resetHoldAccumulator()
                holdAccStep = -1
                c.moveTo(target)
            }
        }
    }

    /** The held turn is confirmed once the gyro has turned the same way by enough. */
    private fun confirmHeldTurn(c: RouteCursor, nowMs: Long) {
        val step = holdStep
        if (step < 0 || step in consumedSteps || holdAccStep != step) return
        val route = c.route
        val si = route.stepS(step)
        val turn = route.turnAngleAt(si)
        val yaw = motion.holdYawDeg
        if (yaw * turn <= 0 || abs(yaw) < max(tuning().turnMinDeg, abs(turn) * 0.6)) return
        snapPastTurn(c, step, si + 20.0, nowMs, "hold")
    }

    /** Any clear gyro turn is matched against route turns near the marker (turn-signature map matching). */
    private fun matchGyroTurn(c: RouteCursor, nowMs: Long) {
        val t = tuning()
        val yaw = motion.integratedYaw(nowMs, t.turnWindowMs)
        if (abs(yaw) < t.turnMinDeg) return
        val route = c.route
        var best = -1
        var bestErr = t.turnTolDeg
        for (i in route.steps.indices) {
            if (i in consumedSteps || route.steps[i].isDepartOrArrive) continue
            val si = route.stepS(i)
            if (si < c.s - t.turnBehindM || si > c.s + t.turnAheadM) continue
            val turn = route.turnAngleAt(si)
            if (abs(turn) < 0.8 * t.turnMinDeg) continue
            val err = abs(turn - yaw)
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        if (best < 0) return
        snapPastTurn(c, best, route.stepS(best) + 20.0, nowMs, "gyro", yaw)
    }

    private fun snapPastTurn(c: RouteCursor, step: Int, toS: Double, nowMs: Long, src: String, yaw: Double = motion.holdYawDeg) {
        consumedSteps += step
        log("turn_snap step=$step gyro=${yaw.toInt()} from_s=${c.s.toInt()} to_s=${toS.toInt()} src=$src")
        c.moveTo(toS)
        catchUp = 0.0
        clearHold()
        motion.resetHoldAccumulator()
        holdAccStep = -1
        motion.turnResetMs = nowMs
    }

    private fun clearHold() {
        holdStep = -1
        holdS = Double.MAX_VALUE
    }

    /** A big gyro rotation that the route cannot explain means a U-turn / wrong road. */
    private fun checkBlindUturn(c: RouteCursor, nowMs: Long) {
        val t = tuning()
        if (!t.blindDeviationEnabled || !deviation.canOffer(nowMs) || rerouting) return
        val yaw = motion.integratedYaw(nowMs, t.turnWindowMs)
        if (abs(yaw) < t.blindDeviationMinDeg) return
        motion.turnResetMs = nowMs
        val curve = routeCurveMatching(c, yaw)
        if (curve != null) {
            log("blind_deviation_skip gyro=${yaw.toInt()} route_curve=${curve.toInt()}")
            return
        }
        offerDeviation(nowMs, "blind_deviation gyro=${yaw.toInt()} delay_s=${t.blindDeviationDelayS}", phrases.blindUturn(t.blindDeviationDelayS))
    }

    /** Largest same-direction heading change ≥ 70% of [yaw] within any 400 m of road around the marker. */
    private fun routeCurveMatching(c: RouteCursor, yaw: Double): Double? {
        val route = c.route
        val from = max(c.s - 400.0, 0.0)
        val to = min(c.s + 300.0, route.length)
        if (to - from < 20.0) return null
        val cumulative = ArrayList<Double>()
        var prev = route.bearingAt(from)
        var total = 0.0
        cumulative += 0.0
        var x = from + 10.0
        while (x <= to) {
            val b = route.bearingAt(x)
            total += Geo.angleDiff(prev, b)
            cumulative += total
            prev = b
            x += 10.0
        }
        val need = abs(yaw) * 0.7
        var best: Double? = null
        for (i in cumulative.indices) {
            for (j in i + 1..min(cumulative.size - 1, i + 40)) {
                val d = cumulative[j] - cumulative[i]
                if (d * yaw > 0 && abs(d) >= need && (best == null || abs(d) > abs(best))) best = d
            }
        }
        return best
    }

    /** If the compass consistently disagrees with the road, jump to the nearest stretch that fits it. */
    private fun compassSnap(c: RouteCursor, headingDeg: Float?, v: Double, nowMs: Long) {
        if (headingDeg == null || v < 3.0 || nowMs - lastCompassSnapMs < 30_000) {
            compassMismatchSinceMs = 0L
            return
        }
        val route = c.route
        val heading = headingDeg.toDouble()
        if (Geo.absAngleDiff(route.bearingAt(c.s), heading) <= 50.0) {
            compassMismatchSinceMs = 0L
            return
        }
        if (compassMismatchSinceMs == 0L) {
            compassMismatchSinceMs = nowMs
            return
        }
        if (nowMs - compassMismatchSinceMs < 6000) return
        val netHint = net.recent.lastOrNull()?.takeIf { nowMs - it.elapsedMs <= 15_000 && it.accM <= 60.0 }
        val low = max(c.s - 150.0, lastConfirmedTurnS(c) ?: 0.0)
        val high = minOf(c.s + if (netHint != null) 300.0 else 150.0, route.length, holdS)
        val center = netHint?.s ?: c.s
        var best: Double? = null
        var x = low
        while (x <= high) {
            var fits = true
            var y = x
            while (y <= x + 40.0) {
                if (Geo.absAngleDiff(route.bearingAt(min(y, route.length)), heading) > 25.0) {
                    fits = false
                    break
                }
                y += 10.0
            }
            if (fits && (best == null || abs(x - center) < abs(best - center))) best = x
            x += 10.0
        }
        val target = best ?: return
        if (netHint != null && abs(target - netHint.s) > netHint.accM + 100.0) return
        log("compass_snap from_s=${c.s.toInt()} to_s=${target.toInt()} heading=${heading.toInt()} route=${route.bearingAt(c.s).toInt()}")
        c.moveTo(target)
        catchUp = 0.0
        lastCompassSnapMs = nowMs
        compassMismatchSinceMs = 0L
    }

    /** When the car stops just before a traffic signal, assume it is queued ~40 m before it. */
    private fun signalSnap(c: RouteCursor) {
        var best = -1
        var bestScore = Double.MAX_VALUE
        for ((i, h) in hazards.withIndex()) {
            if (h.kind != HazardKind.TRAFFIC_SIGNAL || i in usedSignals) continue
            val d = h.s - c.s
            if (d < -10.0 || d > 90.0) continue
            val score = if (d < 0) -d + 1000.0 else d
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return
        usedSignals += best
        val target = max(hazards[best].s - 40.0, 0.0)
        log("signal_snap idx=$best from_s=${c.s.toInt()} to_s=${target.toInt()} signal_s=${hazards[best].s.toInt()}")
        c.moveTo(target)
        catchUp = 0.0
    }

    // ------------------------------------------------------------------ network

    private fun processNetwork(c: RouteCursor, fix: RawFix?, nowMs: Long) {
        if (fix == null || fix.elapsedMs == lastNetProcessedMs) return
        lastNetProcessedMs = fix.elapsedMs
        if (nowMs - fix.elapsedMs > 30_000) return
        val route = c.route
        val proj = route.project(fix.point, c.s, 250.0, 2500.0, 120.0)
        val acc = fix.accuracyM?.toDouble() ?: 500.0
        checkNetworkDeviation(c, proj, acc, fix, nowMs)

        when (net.gate(fix.elapsedMs, proj.s, acc)) {
            NetworkTracker.GateResult.REJECTED -> {
                if (nowMs - netRejectLogMs >= 10_000) {
                    netRejectLogMs = nowMs
                    log("net_reject s_net=${proj.s.toInt()} s_marker=${c.s.toInt()} acc=${acc.toInt()}")
                }
                return
            }
            NetworkTracker.GateResult.REANCHORED -> {
                net.clearSamples()
                log("net_reanchor s_net=${proj.s.toInt()} s_marker=${c.s.toInt()}")
            }
            NetworkTracker.GateResult.ACCEPTED -> Unit
        }
        net.record(NetSample(fix.elapsedMs, proj.s, acc, proj.offsetM), fix.lat, fix.lon)
    }

    /** Three network fixes in a row far from the route ⇒ offer a reroute. */
    private fun checkNetworkDeviation(c: RouteCursor, proj: Projection, acc: Double, fix: RawFix, nowMs: Long) {
        val t = tuning()
        val gpsRecent = lastGpsUseMs > 0 && nowMs - lastGpsUseMs < 3000
        if (gpsRecent || rerouting || !t.blindDeviationEnabled) {
            netDevFastCount = 0
            netDevCount = 0
            netDevFastKey = null
            return
        }
        val remaining = c.route.length - c.s
        val off = proj.offsetM
        if (remaining >= 1000.0 && acc <= 300.0) {
            val key = "${fix.lat},${fix.lon}"
            if (key != netDevFastKey) {
                netDevFastKey = key
                if (off >= max(100.0, 30.0 + 2.5 * acc)) netDevFastCount++ else if (off <= acc + 30.0) netDevFastCount = 0
                if (netDevFastCount >= 3) {
                    netDevFastCount = 0
                    netDevCount = 0
                    if (deviation.canOffer(nowMs)) {
                        offerDeviation(nowMs, "blind_deviation_net off=${off.toInt()} acc=${acc.toInt()} rule=fast delay_s=${t.blindDeviationDelayS}", phrases.blindOffRoute(t.blindDeviationDelayS))
                    }
                    return
                }
            }
        }
        val threshold = if (remaining < 1000.0) 400.0 else 250.0
        if (acc > 300.0 || off < acc + 150.0 || off < threshold) {
            netDevCount = 0
            return
        }
        if (++netDevCount < 3) return
        netDevCount = 0
        if (deviation.canOffer(nowMs)) {
            offerDeviation(nowMs, "blind_deviation_net off=${off.toInt()} acc=${acc.toInt()} delay_s=${t.blindDeviationDelayS}", phrases.blindOffRoute(t.blindDeviationDelayS))
        }
    }

    /**
     * Keep the marker within [s_net ± (acc + 300)] of the latest consistent network fix, and
     * when it lags, schedule a Kalman-style catch-up: gain = σ_dr² / (σ_dr² + (acc+30)²),
     * σ_dr = min(600, 25 + 0.08·distance dead-reckoned).
     */
    private fun bandCorrection(c: RouteCursor, fix: RawFix?, nowMs: Long, dt: Double, stopped: Boolean) {
        val latest = net.recent.lastOrNull() ?: return
        if (fix == null || latest.elapsedMs != fix.elapsedMs || nowMs - latest.elapsedMs >= 30_000) return
        if (!net.lastTwoConsistent()) return
        val band = latest.accM + 300.0
        val maxStep = MAX_SPEED_MPS * dt
        if (!stopped && c.s > latest.s + band) c.moveTo(max(latest.s + band, c.s - maxStep))
        if (c.s < latest.s - band) {
            var target = min(c.s + maxStep, latest.s - band)
            if (holdS > c.s) target = min(target, holdS)
            c.moveTo(target)
        }
        if (!stopped && latest.elapsedMs != lastCatchupFixMs) {
            lastCatchupFixMs = latest.elapsedMs
            val gap = latest.s - c.s
            if (latest.accM <= 100.0 && gap > 0) {
                val sigmaDr = min(600.0, 25.0 + 0.08 * drDistance)
                val r = latest.accM + 30.0
                val gain = gap * sigmaDr * sigmaDr / (sigmaDr * sigmaDr + r * r)
                if (gain > 1.0) catchUp = gain
            }
        }
    }

    private fun applyCatchUp(c: RouteCursor, dt: Double) {
        if (catchUp <= 0.0 || c.s >= holdS) return
        val step = minOf(catchUp, 15.0 * dt, holdS - c.s)
        c.advance(step)
        catchUp -= step
    }

    /** If the marker has run far ahead of every recent network fix, pull it back to their weighted mean. */
    private fun netBack(c: RouteCursor, nowMs: Long) {
        net.pruneHistory(nowMs)
        if (nowMs - lastNetBackMs < 30_000 || net.history.isEmpty()) return
        val usable = net.history.filter { nowMs - it.elapsedMs in 0..30_000 && it.accM <= 150.0 }
        if (usable.size < 3) return
        val predicted = usable.map { it.s + currentSpeed * (nowMs - it.elapsedMs) / 1000.0 }
        if (usable.indices.any { c.s - predicted[it] < max(200.0, usable[it].accM * 2.0) }) return
        val weights = usable.map { 1.0 / (it.accM * it.accM) }
        val mean = usable.indices.sumOf { weights[it] * predicted[it] } / weights.sum()
        var target = max(mean, c.s - 300.0)
        lastConfirmedTurnS(c)?.let { target = max(target, it) }
        if (target >= c.s) return
        lastNetBackMs = nowMs
        log("net_back from_s=${c.s.toInt()} to_s=${target.toInt()} n=${usable.size}")
        c.moveTo(target)
        catchUp = 0.0
    }

    private fun lastConfirmedTurnS(c: RouteCursor): Double? =
        consumedSteps.map { c.route.stepS(it) + 20.0 }.filter { it <= c.s }.maxOrNull()

    // ------------------------------------------------------------------ deviation / reroute

    private fun offerDeviation(nowMs: Long, logLine: String, speech: String) {
        val from = trustedPoint ?: return
        if (!deviation.canOffer(nowMs)) return
        deviationFrom = from
        deviation.pendingUntilMs = nowMs + tuning().blindDeviationDelayS * 1000L
        log(logLine)
        say(speech, urgent = true)
    }

    private fun deviationTick(nowMs: Long) {
        if (!deviation.pending || nowMs < deviation.pendingUntilMs) return
        deviation.clear(nowMs, 90_000)
        log("blind_deviation_reroute auto=true")
        deviationFrom?.let { requestReroute(it, auto = true) }
    }

    private fun requestReroute(from: GeoPoint, auto: Boolean) {
        val c = cursor ?: return
        val dest = destination ?: return
        if (rerouting) return
        rerouting = true
        val via = waypointS.filter { it.second > c.s + 30.0 }.map { it.first }
        log("reroute_from %.5f %.5f via=%d".format(from.lat, from.lon, via.size))
        publishFlags()
        listener.onRerouteRequested(from, dest, via, auto)
    }

    // ------------------------------------------------------------------ output

    private fun publish(c: RouteCursor, pos: PositioningSnapshot, nowMs: Long) {
        val t = tuning()
        val route = c.route
        val s = c.s
        val point = route.pointAt(s)
        val next = route.steps.indices.firstOrNull { route.steps[it].type != "depart" && route.stepS(it) > s + 8.0 } ?: -1
        val nextStep = route.steps.getOrNull(next)
        val distToNext = if (next >= 0) route.stepS(next) - s else 0.0
        val remaining = route.length - s
        val arrived = remaining < t.arriveM
        val blindS = ((nowMs - if (lastGpsUseMs > 0) lastGpsUseMs else navStartMs) / 1000).toInt()
        val netFresh = pos.lastNet?.let { nowMs - it.elapsedMs < 30_000 } == true
        val uncertainty = if (source.isGps) {
            15.0
        } else {
            // Drift grows ~8 % of distance dead-reckoned on top of the anchor's own error: 25 m after
            // a GPS fix, or the start position's accuracy if GPS has not been usable yet this trip.
            val anchor = if (lastGpsUseMs > 0) 25.0 else max(25.0, startAccuracyM)
            val cap = max(if (netFresh) 350.0 else 600.0, anchor)
            max(30.0, min(cap, anchor + 0.08 * drDistance))
        }

        announce(next, nextStep, distToNext, arrived, blindS)

        state = state.copy(
            active = true,
            route = route,
            s = s,
            position = point.point,
            bearingDeg = point.bearingDeg.toFloat(),
            nextStep = nextStep,
            nextStepIndex = next,
            distToNextM = distToNext,
            thenStep = if (next >= 0) route.steps.getOrNull(next + 1) else null,
            remainingM = remaining,
            remainingS = if (route.length > 0) route.durationS * remaining / route.length else 0.0,
            speedKmh = (currentSpeed * 3.6).toFloat(),
            speedLimitKmh = route.maxspeedAtSegment(point.segment),
            offRoute = offRouteDeclared,
            offRouteM = offRouteM,
            arrived = arrived,
            source = source,
            rerouting = rerouting,
            destination = destination,
            blindS = blindS,
            uncertaintyM = uncertainty,
            jamRecovering = recovery.active(nowMs),
            blindDeviation = deviation.pending,
            blindDeviationSecLeft = if (deviation.pending) max(0L, (deviation.pendingUntilMs - nowMs) / 1000).toInt() else 0,
        )
    }

    private fun publishFlags() {
        state = state.copy(rerouting = rerouting, blindDeviation = deviation.pending)
    }

    private fun announce(next: Int, step: org.blinddriver.core.route.Step?, dist: Double, arrived: Boolean, blindS: Int) {
        if (arrived) {
            if (!arrivedAnnounced) {
                arrivedAnnounced = true
                say(phrases.arrived(), urgent = false)
            }
            return
        }
        if (source.isGps && gpsLostAnnounced) {
            gpsLostAnnounced = false
            say(phrases.gpsRestored(), urgent = false)
        } else if (!source.isGps && source != PositionSource.NONE && lastGpsUseMs > 0 && !gpsLostAnnounced && blindS >= 5) {
            gpsLostAnnounced = true
            say(phrases.gpsLost(), urgent = false)
        }
        if (step == null || next < 0) return
        val level = ANNOUNCE_AT_M.indexOfLast { dist <= it }
        if (level < 0) return
        val key = next * 10 + level
        if (key in announced) return
        for (l in 0..level) announced += next * 10 + l
        if (level == 0 && currentSpeed < 14.0) return
        say(phrases.maneuver(step, if (level == ANNOUNCE_AT_M.lastIndex) null else dist), urgent = level >= 2)
    }

    private fun say(text: String, urgent: Boolean) {
        log("say $text")
        listener.onSay(text, urgent)
    }

    private fun log(message: String) = listener.onLog(message)

    // ------------------------------------------------------------------ helpers

    /**
     * Moves the marker smoothly towards a GPS anchor extrapolated with GPS speed: never backwards by
     * less than 25 m, and forward with a 0.4 s time constant.
     */
    private class MarkerSmoother {
        var valid = false
        private var anchorS = 0.0
        private var anchorMs = 0L
        private var speed = 0.0

        fun anchor(s: Double, elapsedMs: Long, speedMps: Double) {
            anchorS = s
            anchorMs = elapsedMs
            speed = speedMps
            valid = true
        }

        fun follow(current: Double, nowMs: Long, dt: Double, snap: Boolean): Double {
            if (!valid) return current
            val predicted = anchorS + speed * ((nowMs - anchorMs) / 1000.0).coerceIn(0.0, 1.6)
            val diff = predicted - current
            if (snap || abs(diff) > 25.0) return predicted
            if (diff > 0) return current + (1.0 - exp(-max(dt, 0.0) / 0.4)) * diff
            return current
        }
    }

    /**
     * After jamming ends or GPS comes back from LOST, SUSPECT fixes are accepted with a wider jump
     * limit (600 m) for up to 5 minutes or 10 consecutive GOOD fixes.
     */
    private class JamRecovery {
        private var untilMs = 0L
        private var wasJammed = false
        private var wasLost = false
        private var goodInRow = 0

        fun update(jammed: Boolean, lost: Boolean, nowMs: Long) {
            if ((wasJammed && !jammed) || (wasLost && !lost)) {
                untilMs = nowMs + 300_000
                goodInRow = 0
            }
            wasJammed = jammed
            wasLost = lost
        }

        fun onFix(good: Boolean) {
            goodInRow = if (good) goodInRow + 1 else 0
        }

        fun active(nowMs: Long): Boolean = nowMs < untilMs && goodInRow < 10
    }

    companion object {
        const val TICK_MS = 500L
        private val ANNOUNCE_AT_M = listOf(1000.0, 400.0, 150.0, 40.0)
    }
}
