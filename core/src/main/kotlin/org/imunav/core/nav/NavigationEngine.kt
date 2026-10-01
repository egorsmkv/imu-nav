package org.imunav.core.nav

import org.imunav.core.Tuning
import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.JudgedFix
import org.imunav.core.gnss.PositioningSnapshot
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.MotionDetector
import org.imunav.core.imu.Pedometer
import org.imunav.core.imu.TurnDetector
import org.imunav.core.imu.TurnEvidence
import org.imunav.core.route.Hazard
import org.imunav.core.route.HazardKind
import org.imunav.core.route.Projection
import org.imunav.core.route.Route
import org.imunav.core.route.RouteCursor
import org.imunav.core.route.RouteProjector
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import org.imunav.core.speed.MAX_SPEED_MPS
import org.imunav.core.speed.RouteSpeedPrior
import org.imunav.core.speed.SpeedEstimate
import org.imunav.core.speed.SpeedFusion
import org.imunav.core.speed.SpeedFusionProvider
import org.imunav.core.speed.SpeedPlan
import org.imunav.core.speed.SpeedProfile
import java.util.Locale
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
 *  3. otherwise `s += speedMps·dt·motionFactor`, where speedMps fuses GPS / route prior / network speed
 *     (or is the car's own speed from an OBD-II adapter, when one is connected);
 *  4. landmark corrections snap `s`: turn-hold + gyro confirmation, gyro turn matching,
 *     compass heading, stop at traffic signal, barometer vs. route elevation (terrain matching),
 *     network band / catch-up / pull-back;
 *  5. deviations (U-turn, missed turn, network off-route, GPS off-route) trigger reroutes.
 */
class NavigationEngine(
    private val tuning: () -> Tuning = { Tuning.DEFAULT },
    private val navigationMethod: () -> NavigationMethod = { NavigationMethod.HYBRID },
    private val speedProfile: SpeedProfile = SpeedProfile(),
    /** Spoken phrase set; may be switched at runtime (language change). */
    var phrases: Phrases = UkrainianPhrases,
    private val listener: NavListener,
    /** Extra traffic-calming points (speed bumps) to consider on every route. */
    private val trafficCalming: List<GeoPoint> = emptyList(),
    /** Route-projected cell/network gate; Android injects the native implementation. */
    private val networkTracker: NetworkPositionTracker = NetworkTracker(),
    /** Geographic observation projection; Android injects Rust geometry. */
    private val routeProjector: RouteProjector = RouteProjector.KOTLIN,
    /** Inverse-variance speed fusion; Android injects the native implementation. */
    private val speedFusion: SpeedFusionProvider = SpeedFusion,
) {
    val motion = MotionDetector(tuning).also { it.log = ::log }
    private val comparisonTurns = TurnDetector()

    /** Walking speed from the step detector (used in [TravelMode.FOOT]). */
    val pedometer = Pedometer()

    /** Car or on foot; set by [start]. */
    var mode: TravelMode = TravelMode.CAR
        private set

    /** The thresholds in effect: the user's [Tuning], adapted for walking in [TravelMode.FOOT]. */
    private fun settings(): Tuning = if (mode == TravelMode.FOOT) tuning().forWalking() else tuning()

    var state = GuidanceState()
        private set

    /** Reroute automatically when GPS says we left the route; otherwise only announce it. */
    var autoReroute = true

    /** Debug: ignore GPS entirely, as if it were jammed. */
    var simulateGpsLoss = false

    /** Where the car is on the current route; null when not navigating. */
    private var cursor: RouteCursor? = null
    private var destination: GeoPoint? = null
    private var waypoints: List<GeoPoint> = emptyList()
    private var waypointS: List<Pair<GeoPoint, Double>> = emptyList()
    private var hazards: List<Hazard> = emptyList()
    private var rerouting = false
    private var navStartMs = 0L
    private var lastTickMs = -1L
    private var source = PositionSource.NONE
    private var activeNavigationMethod = navigationMethod()

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

    /** How far off dead reckoning may have drifted since the last anchor (GPS, cell or terrain fix), metres. */
    private var drDriftM = 0.0
    private var lastCellProcessedMs = -1L
    private var cellAccuracyM = 0.0

    /** Accuracy of the trip's start position; applies until GPS is first used. */
    private var startAccuracyM = 0.0
    private var catchUp = 0.0
    private var lastCatchupFixMs = -1L
    private var cachedNet: SpeedEstimate? = null
    private var cachedNetAtMs = 0L
    private val motionHistory = ArrayDeque<Pair<Long, Double>>()
    private var netOverridesStop = false
    private var wasStopped = false

    // Car speed from an OBD-II adapter (see [onVehicleSpeed])
    private var vehicleSpeedMps: Double? = null
    private var vehicleSpeedAtMs = -1L

    /** GPS speed ÷ OBD speed, learned while GPS is trusted (car speedometers read a little high or low). */
    private var vehicleSpeedScale = 1.0
    private var vehicleSpeedInUse = false

    // Terrain matching (barometer vs. route elevation)

    /** Height history and matching; also exposes the smoothed barometric height. */
    val elevation = ElevationMatcher()

    /** Distance travelled according to speed × time, never corrected (terrain matching needs raw odometry). */
    private var odometerM = 0.0
    private var lastTerrainTryMs = -TERRAIN_EVERY_MS

    // Network
    private val net = networkTracker
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
    fun start(route: Route, destination: GeoPoint, waypoints: List<GeoPoint> = emptyList(), nowMs: Long, startAccuracyM: Double = 0.0, mode: TravelMode = TravelMode.CAR) {
        this.mode = mode
        pedometer.reset()
        this.startAccuracyM = startAccuracyM.coerceAtLeast(0.0)
        this.destination = destination
        this.waypoints = waypoints
        navStartMs = nowMs
        lastGpsUseMs = 0L
        lastGpsProcessedMs = -1L
        lastGpsSpeed = null
        source = PositionSource.NONE
        activeNavigationMethod = navigationMethod()
        lastCellProcessedMs = -1L
        cellAccuracyM = 0.0
        gpsLostAnnounced = false
        elevation.reset()
        odometerM = 0.0
        installRoute(route, nowMs)
        log("nav_start len=${route.length.toInt()} mode=$mode")
    }

    /**
     * Continue a trip restored after the app was killed: jump to [s] on the already started route.
     * Call right after [start] (with the restored uncertainty as start accuracy).
     */
    fun resumeAt(s: Double) {
        val car = cursor ?: return
        car.moveTo(s)
        // Turns already behind the saved position were driven.
        car.route.steps.indices.filter { car.route.stepS(it) < s - 1.0 }.forEach { consumedSteps += it }
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
        listener.onAlert(NavAlert.REROUTED)
        say(phrases.rerouted(), urgent = false)
    }

    /** The router could not answer: allow new reroute requests. */
    fun rerouteFailed() {
        rerouting = false
        publishFlags()
    }

    /** End navigation. */
    fun stop() {
        cursor?.let { log("nav_stop s=${it.s.toInt()}") }
        cursor = null
        destination = null
        state = GuidanceState()
    }

    /**
     * The car's own speed from an OBD-II adapter (km/h). While fresh, it replaces the speed guess in
     * dead reckoning: it is exact to a few percent and does not care about jamming.
     */
    fun onVehicleSpeed(kmh: Double, elapsedMs: Long) {
        if (kmh !in 0.0..MAX_VEHICLE_KMH) return
        vehicleSpeedMps = kmh / 3.6
        vehicleSpeedAtMs = elapsedMs
    }

    /** A barometer reading (hPa), for terrain matching. */
    fun onPressure(hPa: Double, elapsedMs: Long) = elevation.onPressure(hPa, elapsedMs)

    /** One step from the phone's step detector (only used on foot). */
    fun onStep(elapsedMs: Long) = pedometer.onStep(elapsedMs)

    /** Feed every IMU sample (tens per second) to the stop / turn detectors. */
    fun onImu(sample: ImuSample, yawBiasDegS: Double = 0.0) {
        motion.add(sample, yawBiasDegS)
        comparisonTurns.add(sample, yawBiasDegS)
    }

    /** Exports recorded rotation evidence, never the live engine's turn snaps or route position. */
    fun turnEvidence(nowMs: Long): TurnEvidence? = if (cursor != null && mode == TravelMode.CAR) comparisonTurns.evidence(nowMs) else null

    /**
     * Shares recorded motion evidence with the native comparison estimator. Network movement
     * requires a recent non-duplicate fix and a positive speed lower bound, so cached tower
     * locations cannot keep vetoing a real stop. No engine position correction is exported.
     */
    fun motionEvidence(nowMs: Long): MotionEvidence? {
        if (mode != TravelMode.CAR) return null
        val car = cursor ?: return null
        val factor = motion.motionFactor(nowMs) ?: return null
        val networkFresh = net.history.lastOrNull()?.let { nowMs - it.elapsedMs in 0..MOTION_NETWORK_MAX_AGE_MS } == true
        val network = if (networkFresh) net.speedEstimate(nowMs) else null
        val networkMoving = network != null && network.speedMps - MOTION_NETWORK_SIGMAS * network.sigmaMps > MOTION_NETWORK_MIN_SPEED_MPS
        val ageMs = nowMs - if (lastGpsUseMs > 0) lastGpsUseMs else navStartMs
        val cruise = speedFusion.fuse(lastGpsSpeed, ageMs, RouteSpeedPrior.at(car.route, car.s, speedProfile), network)
        return MotionEvidence(factor, cruise, motion.validUntilMs, networkMoving)
    }

    /** User accepted the "you left the route" countdown early. */
    fun confirmDeviation(nowMs: Long) {
        if (!deviation.pending) return
        deviation.clear(nowMs, 90_000)
        deviationFrom?.let { requestReroute(it, auto = false) }
        publishFlags()
    }

    /** User said "no, I am still on the route". */
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

    /** Start following [route] from its beginning and reset all per-route state. */
    private fun installRoute(route: Route, nowMs: Long) {
        comparisonTurns.reset()
        val car = RouteCursor(route)
        cursor = car
        hazards = route.hazards(trafficCalming)
        waypointS = waypoints.map { it to routeProjector.project(route, it, 0.0, 0.0, route.length, 0.0).s }
        consumedSteps.clear()
        usedSignals.clear()
        announced.clear()
        net.reset()
        lastCellProcessedMs = -1L
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

    /** One engine step (every [TICK_MS]): read positioning, move the marker, publish [state]. */
    fun tick(nowMs: Long, pos: PositioningSnapshot) {
        val car = cursor ?: return
        val dt = if (lastTickMs < 0) 0.0 else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 5.0)
        lastTickMs = nowMs
        recovery.update(pos.jammed, pos.gpsState == GpsState.LOST || simulateGpsLoss, nowMs)

        updateNavigationMethod()
        if (activeNavigationMethod == NavigationMethod.HYBRID) {
            processNetwork(car, pos.lastNet, nowMs)
            if (lastGpsUseMs == 0L || nowMs - lastGpsUseMs >= 3000) netBack(car, nowMs)
        }

        val gps = if (simulateGpsLoss) null else pos.lastUsableGps
        val usedGps = gps != null && gps.fix.elapsedMs > lastGpsProcessedMs && handleGps(car, gps, nowMs, dt)
        if (!usedGps) {
            // GPS fixes arrive once per second but we tick twice: between fixes, keep gliding.
            if (!simulateGpsLoss && lastGpsUseMs > 0 && nowMs - lastGpsUseMs < 3000 && source.isGps) {
                if (smoother.valid) car.moveTo(smoother.follow(car.s, nowMs, dt, snap = false))
            } else {
                when (activeNavigationMethod) {
                    NavigationMethod.DEAD_RECKONING -> deadReckon(car, pos, networkFix = null, nowMs, dt)
                    NavigationMethod.CELL_TOWERS -> useCellPosition(car, pos.lastCell, nowMs)
                    NavigationMethod.HYBRID -> deadReckon(car, pos, pos.lastNet, nowMs, dt)
                }
            }
        }
        odometerM += currentSpeed * dt
        elevation.onTravel(odometerM)
        if (!source.isGps && source != PositionSource.CELL && source != PositionSource.NONE) terrainMatch(car, nowMs)
        deviationTick(nowMs)
        publish(car, pos, nowMs)
    }

    /** Reset method-specific history when the user changes the fallback while navigating. */
    private fun updateNavigationMethod() {
        val selected = navigationMethod()
        if (selected == activeNavigationMethod) return
        activeNavigationMethod = selected
        net.reset()
        lastNetProcessedMs = -1L
        lastCellProcessedMs = -1L
        cachedNet = null
        catchUp = 0.0
        log("nav_method method=$selected")
    }

    // ------------------------------------------------------------------ GPS

    /**
     * Use a GPS fix if we can trust it: GOOD fixes always, SUSPECT ones only when they are close
     * to the route and to our own estimate. Returns true if the fix moved the marker.
     */
    private fun handleGps(car: RouteCursor, judged: JudgedFix, nowMs: Long, dt: Double): Boolean {
        val fix = judged.fix
        lastGpsProcessedMs = fix.elapsedMs
        val route = car.route
        val proj = routeProjector.project(route, fix.point, car.s, 250.0, 2500.0, 120.0)
        val good = judged.verdict.level == TrustLevel.GOOD
        recovery.onFix(good)
        val inRecovery = recovery.active(nowMs)
        val maxJump = if (inRecovery) 600.0 else 300.0
        val accOk = !inRecovery || (fix.accuracyM ?: Float.MAX_VALUE) <= 150f
        val consistent = proj.offsetM < 60.0 && abs(proj.s - car.s) < maxJump && accOk
        if (!good && !consistent) return false

        if (deviation.pending) {
            deviation.clear(nowMs, 90_000)
            log("blind_deviation_gps_return")
        }
        val speed = fix.speedMps?.toDouble()
        if (proj.s >= car.s - 40.0 || proj.offsetM < 25.0) {
            smoother.anchor(proj.s, fix.elapsedMs, speed?.takeIf { it in 0.0..70.0 } ?: 0.0)
            car.moveTo(smoother.follow(car.s, nowMs, dt, snap = !source.isGps))
        }
        drDriftM = 0.0
        catchUp = 0.0
        if (speed != null) {
            lastGpsSpeed = speed
            currentSpeed = speed
            if (good) {
                when (mode) {
                    TravelMode.CAR -> {
                        route.maxspeedAtSegment(proj.segment)?.let { speedProfile.learn(speed, it) }
                        learnVehicleSpeedScale(speed, fix.elapsedMs)
                    }

                    TravelMode.FOOT -> pedometer.learnStride(speed, nowMs)
                }
            }
        }
        lastGpsUseMs = fix.elapsedMs
        if (good) trustedPoint = fix.point

        val bearing = fix.bearingDeg
        val courseDiff = if (bearing != null && (speed ?: 0.0) >= 15.0 / 3.6) {
            Geo.absAngleDiff(bearing.toDouble(), route.bearingAt(min(proj.s + 25.0, route.length)))
        } else {
            null
        }
        if (good) checkOffRoute(car, proj, courseDiff, fix, nowMs)
        source = if (good) PositionSource.GPS else PositionSource.GPS_SUSPECT
        return true
    }

    /** With GOOD GPS: have we left the route (far from it for a while, or clearly driving elsewhere)? */
    private fun checkOffRoute(car: RouteCursor, proj: Projection, courseDiff: Double?, fix: RawFix, nowMs: Long) {
        val config = settings()
        offRouteM = proj.offsetM
        if (car.route.length - proj.s < max(config.arriveM, 15.0)) {
            offRouteSinceMs = -1L
            offRouteFastSinceMs = -1L
            offRouteDeclared = false
            return
        }
        val fast = courseDiff != null && proj.offsetM > config.offRouteFastM && courseDiff > config.offRouteFastDeg
        val far = proj.offsetM > config.offRouteM
        if (fast) {
            if (offRouteFastSinceMs < 0) offRouteFastSinceMs = nowMs
            if (nowMs - offRouteFastSinceMs >= config.offRouteFastHoldMs) declareOffRoute(fix, "fast course_diff=${courseDiff.toInt()}")
        } else {
            offRouteFastSinceMs = -1L
        }
        if (far) {
            if (offRouteSinceMs < 0) offRouteSinceMs = nowMs
            if (nowMs - offRouteSinceMs >= config.offRouteHoldMs) declareOffRoute(fix, "")
        } else {
            offRouteSinceMs = -1L
        }
        if (!fast && !far) offRouteDeclared = false
    }

    private fun declareOffRoute(fix: RawFix, detail: String) {
        if (offRouteDeclared) return
        offRouteDeclared = true
        log("off_route ${offRouteM.toInt()}m $detail".trim())
        listener.onAlert(NavAlert.OFF_ROUTE)
        if (autoReroute) {
            say(phrases.offRouteRerouting(), urgent = true)
            log("auto_reroute")
            requestReroute(fix.point, auto = true)
        } else {
            say(phrases.offRouteAsk(), urgent = true)
        }
    }

    // ------------------------------------------------------------------ dead reckoning

    /**
     * No usable GPS: move the marker by estimated speed × time, then apply every correction we
     * have (turns seen by the gyro, compass, traffic signals, network fixes).
     */
    private fun deadReckon(car: RouteCursor, pos: PositioningSnapshot, networkFix: RawFix?, nowMs: Long, dt: Double) {
        val config = settings()
        val (speedMps, stopped) = when (mode) {
            TravelMode.CAR -> drivingMotion(car, nowMs, config)
            TravelMode.FOOT -> walkingMotion(nowMs)
        }
        currentSpeed = speedMps

        if (speedMps > 0.3) advance(car, speedMps, dt, nowMs)
        if (mode == TravelMode.CAR) {
            // These corrections need a phone fixed in a car holder; a phone in the hand swings around.
            confirmHeldTurn(car, nowMs)
            matchGyroTurn(car, nowMs)
            checkBlindUturn(car, nowMs)
            compassSnap(car, pos.compassDeg, speedMps, nowMs)
        }

        if (config.signalSnap && stopped && !wasStopped) signalSnap(car)
        wasStopped = stopped
        bandCorrection(car, networkFix, nowMs, dt, stopped)

        source = if (stopped) {
            PositionSource.DR_STOPPED
        } else {
            applyCatchUp(car, dt)
            val netFresh = networkFix?.let { nowMs - it.elapsedMs < 30_000 } == true
            when {
                vehicleSpeedInUse && mode == TravelMode.CAR -> PositionSource.DR_OBD
                netFresh -> PositionSource.DR_NET
                else -> PositionSource.DR
            }
        }
    }

    /**
     * Cell-only fallback: put the marker at the latest cell fix projected onto the planned route.
     * The marker intentionally stays there between scans instead of borrowing dead reckoning.
     */
    private fun useCellPosition(car: RouteCursor, fix: RawFix?, nowMs: Long) {
        if (fix == null || nowMs - fix.elapsedMs !in 0..CELL_FIX_MAX_AGE_MS) {
            currentSpeed = 0.0
            source = PositionSource.NONE
            return
        }
        if (fix.elapsedMs != lastCellProcessedMs) {
            val projection = routeProjector.project(car.route, fix.point, car.s, 250.0, 2500.0, 120.0)
            car.moveTo(projection.s)
            lastCellProcessedMs = fix.elapsedMs
            cellAccuracyM = fix.accuracyM?.toDouble() ?: DEFAULT_CELL_ACCURACY_M
            drDriftM = 0.0
            catchUp = 0.0
            log("cell_position s=${projection.s.toInt()} off=${projection.offsetM.toInt()} acc=${cellAccuracyM.toInt()}")
        }
        currentSpeed = 0.0
        source = PositionSource.CELL
    }

    /** Car: fused speed (GPS / route prior / network) scaled by the IMU motion factor. Returns (speed, stopped). */
    private fun drivingMotion(car: RouteCursor, nowMs: Long, config: Tuning): Pair<Double, Boolean> {
        val vehicle = freshVehicleSpeed(nowMs)
        if ((vehicle != null) != vehicleSpeedInUse) {
            vehicleSpeedInUse = vehicle != null
            log("vehicle_speed active=$vehicleSpeedInUse scale=${"%.3f".format(Locale.US, vehicleSpeedScale)}")
        }
        // The car's own speed is a measurement, not a guess: no fusion, motion factor or speed plan.
        if (vehicle != null) return vehicle to (vehicle < VEHICLE_STOPPED_MPS)
        val route = car.route
        val sinceGps = nowMs - if (lastGpsUseMs > 0) lastGpsUseMs else navStartMs
        val factor = motion.motionFactor(nowMs)
        val netSpeed = networkSpeed(nowMs, factor)

        val base = speedFusion.fuse(lastGpsSpeed, sinceGps, RouteSpeedPrior.at(route, car.s, speedProfile), netSpeed)
        // A long, consistent network speed while the IMU says "stopped" means we are in fact
        // moving smoothly (e.g. on a highway with a phone in a soft holder).
        val strict = net.strictSpeedEstimate(nowMs)
        val override = strict != null && strict.speedMps >= 4.0 && strict.sigmaMps <= 1.5 && strict.spanS >= 45.0 && motion.accMean >= 0.08
        if (override != netOverridesStop && factor == 0.0) log("net_overrides_stop active=$override netv=${((strict?.speedMps ?: 0.0) * 3.6).toInt()}")
        netOverridesStop = override

        var speedMps = drSpeed(base, factor, override, sinceGps)
        if (config.speedPlan && hazards.isNotEmpty()) SpeedPlan.cap(hazards, car.s, netSpeed == null)?.let { speedMps = min(speedMps, it) }
        return speedMps to (motion.stopped && !override)
    }

    /** OBD-II speed corrected by the learned scale, if a reading arrived in the last [VEHICLE_SPEED_MAX_AGE_MS]. */
    private fun freshVehicleSpeed(nowMs: Long): Double? {
        val speed = vehicleSpeedMps ?: return null
        if (mode != TravelMode.CAR || nowMs - vehicleSpeedAtMs !in 0..VEHICLE_SPEED_MAX_AGE_MS) return null
        return speed * vehicleSpeedScale
    }

    /** With trusted GPS: learn how the adapter's speed relates to the real one (tyre wear, speedometer offset). */
    private fun learnVehicleSpeedScale(gpsSpeed: Double, fixMs: Long) {
        val vehicle = vehicleSpeedMps ?: return
        if (gpsSpeed < SCALE_LEARN_MIN_MPS || vehicle < SCALE_LEARN_MIN_MPS || abs(fixMs - vehicleSpeedAtMs) > 1000) return
        val ratio = gpsSpeed / vehicle
        if (ratio !in 0.8..1.2) return // acceleration between the two readings, or a bad fix
        vehicleSpeedScale += (ratio - vehicleSpeedScale) * 0.05
    }

    /** Dead-reckoning drift per metre: ~8 % with estimated speed, ~2 % with the car's own speed. */
    private fun driftPerMetre(): Double = if (vehicleSpeedInUse) VEHICLE_SPEED_DRIFT else ESTIMATED_SPEED_DRIFT

    /**
     * On foot: steps × stride from the step detector. Phones without one fall back to a normal
     * walking pace while the IMU sees movement. Returns (speed, stopped).
     */
    private fun walkingMotion(nowMs: Long): Pair<Double, Boolean> {
        pedometer.speed(nowMs)?.let { speed -> return speed to (speed == 0.0) }
        val factor = motion.motionFactor(nowMs)
        if (factor == 0.0 || motion.stopped) return 0.0 to true
        return WALKING_SPEED_MPS * (factor ?: 1.0) to false
    }

    /**
     * Network-derived speed: fresh estimate, else a recent one with growing σ (dropped once we stop),
     * rescaled by the moving duty cycle because it averages over stops.
     */
    private fun networkSpeed(nowMs: Long, factor: Double?): SpeedEstimate? {
        var netSpeed = net.speedEstimate(nowMs)
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
        if (netSpeed != null && motionHistory.isNotEmpty()) {
            val duty = motionHistory.sumOf { it.second } / motionHistory.size
            if (duty >= 0.25) netSpeed = netSpeed.copy(speedMps = min(netSpeed.speedMps / duty, MAX_SPEED_MPS))
        }
        return netSpeed
    }

    /** Dead-reckoning speed: fused speed scaled by the IMU motion factor; without IMU, the last GPS speed fading out. */
    private fun drSpeed(base: Double, factor: Double?, netOverride: Boolean, sinceGps: Long): Double {
        val gpsSpeed = lastGpsSpeed
        return when {
            factor == 0.0 && netOverride -> base
            factor != null -> factor * base
            gpsSpeed != null && sinceGps < 120_000 -> gpsSpeed
            gpsSpeed != null && sinceGps < 180_000 -> gpsSpeed * (1.0 - (sinceGps - 120_000) / 60_000.0)
            else -> base
        }
    }

    /** First not-yet-passed route turn of at least [Tuning.turnMinDeg] at or ahead of the marker. */
    private fun nextHoldableTurn(car: RouteCursor, config: Tuning): Triple<Int, Double, Double>? {
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
    private fun advance(car: RouteCursor, speedMps: Double, dt: Double, nowMs: Long) {
        val config = settings()
        val ds = speedMps * dt
        drDriftM += ds * driftPerMetre()
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
                phrases.blindMissedTurn(config.blindDeviationDelayS),
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
    private fun confirmHeldTurn(car: RouteCursor, nowMs: Long) {
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
    private fun matchGyroTurn(car: RouteCursor, nowMs: Long) {
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
    private fun checkBlindUturn(car: RouteCursor, nowMs: Long) {
        val config = settings()
        if (!config.blindDeviationEnabled || !deviation.canOffer(nowMs) || rerouting) return
        val yaw = motion.integratedYaw(nowMs, config.turnWindowMs)
        if (abs(yaw) < config.blindDeviationMinDeg) return
        motion.turnResetMs = nowMs
        val curve = routeCurveMatching(car, yaw)
        if (curve != null) {
            log("blind_deviation_skip gyro=${yaw.toInt()} route_curve=${curve.toInt()}")
            return
        }
        offerDeviation(nowMs, "blind_deviation gyro=${yaw.toInt()} delay_s=${config.blindDeviationDelayS}", phrases.blindUturn(config.blindDeviationDelayS))
    }

    /** Largest same-direction heading change ≥ 70% of [yaw] within any 400 m of road around the marker. */
    private fun routeCurveMatching(car: RouteCursor, yaw: Double): Double? {
        val route = car.route
        val from = max(car.s - 400.0, 0.0)
        val to = min(car.s + 300.0, route.length)
        if (to - from < 20.0) return null
        // Total heading change of the road from `from` to every 10 m step after it.
        val headingChange = ArrayList<Double>()
        var previousBearing = route.bearingAt(from)
        var total = 0.0
        headingChange += 0.0
        var probeS = from + 10.0
        while (probeS <= to) {
            val bearing = route.bearingAt(probeS)
            total += Geo.angleDiff(previousBearing, bearing)
            headingChange += total
            previousBearing = bearing
            probeS += 10.0
        }
        // Heading change over every stretch of up to 40 × 10 m = 400 m.
        val needed = abs(yaw) * 0.7
        var best: Double? = null
        for (i in headingChange.indices) {
            for (j in i + 1..min(headingChange.size - 1, i + 40)) {
                val change = headingChange[j] - headingChange[i]
                if (change * yaw > 0 && abs(change) >= needed && (best == null || abs(change) > abs(best))) best = change
            }
        }
        return best
    }

    /** If the compass consistently disagrees with the road, jump to the nearest stretch that fits it. */
    private fun compassSnap(car: RouteCursor, headingDeg: Float?, speedMps: Double, nowMs: Long) {
        if (headingDeg == null || speedMps < 3.0 || nowMs - lastCompassSnapMs < 30_000) {
            compassMismatchSinceMs = 0L
            return
        }
        val route = car.route
        val heading = headingDeg.toDouble()
        if (Geo.absAngleDiff(route.bearingAt(car.s), heading) <= 50.0) {
            compassMismatchSinceMs = 0L
            return
        }
        if (compassMismatchSinceMs == 0L) {
            compassMismatchSinceMs = nowMs
            return
        }
        if (nowMs - compassMismatchSinceMs < 6000) return
        val netHint = net.recent.lastOrNull()?.takeIf { nowMs - it.elapsedMs <= 15_000 && it.accM <= 60.0 }
        val low = max(car.s - 150.0, lastConfirmedTurnS(car) ?: 0.0)
        val high = minOf(car.s + if (netHint != null) 300.0 else 150.0, route.length, holdS)
        val center = netHint?.s ?: car.s

        // Candidate positions every 10 m; a candidate fits if the next 40 m of road point the way the compass does.
        fun roadFitsHeading(startS: Double) = (0..4).all { k -> Geo.absAngleDiff(route.bearingAt(min(startS + k * 10.0, route.length)), heading) <= 25.0 }
        var best: Double? = null
        var candidateS = low
        while (candidateS <= high) {
            if (roadFitsHeading(candidateS) && (best == null || abs(candidateS - center) < abs(best - center))) best = candidateS
            candidateS += 10.0
        }
        val target = best ?: return
        if (netHint != null && abs(target - netHint.s) > netHint.accM + 100.0) return
        log("compass_snap from_s=${car.s.toInt()} to_s=${target.toInt()} heading=${heading.toInt()} route=${route.bearingAt(car.s).toInt()}")
        car.moveTo(target)
        catchUp = 0.0
        lastCompassSnapMs = nowMs
        compassMismatchSinceMs = 0L
    }

    /** When the car stops just before a traffic signal, assume it is queued ~40 m before it. */
    private fun signalSnap(car: RouteCursor) {
        var best = -1
        var bestScore = Double.MAX_VALUE
        for ((i, hazard) in hazards.withIndex()) {
            if (hazard.kind != HazardKind.TRAFFIC_SIGNAL || i in usedSignals) continue
            val ahead = hazard.s - car.s
            if (ahead < -10.0 || ahead > 90.0) continue
            // Prefer the nearest signal ahead; one just behind us only if there is none ahead.
            val score = if (ahead < 0) -ahead + 1000.0 else ahead
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return
        usedSignals += best
        val target = max(hazards[best].s - 40.0, 0.0)
        log("signal_snap idx=$best from_s=${car.s.toInt()} to_s=${target.toInt()} signal_s=${hazards[best].s.toInt()}")
        car.moveTo(target)
        catchUp = 0.0
    }

    /**
     * Terrain matching: every few seconds, slide the barometer's recent height trace along the route's
     * elevation profile ([ElevationMatcher]). A clear fit moves the marker there; one that confirms the
     * current position shrinks the uncertainty. Never jumps back before a confirmed turn or across a
     * turn the gyro has not confirmed yet — turns are stronger evidence than hills.
     */
    private fun terrainMatch(car: RouteCursor, nowMs: Long) {
        if (!settings().terrainMatch || !car.route.hasElevation || nowMs - lastTerrainTryMs < TERRAIN_EVERY_MS) return
        lastTerrainTryMs = nowMs
        val search = (state.uncertaintyM * 1.5).coerceIn(TERRAIN_MIN_SEARCH_M, TERRAIN_MAX_SEARCH_M)
        val scales = if (vehicleSpeedInUse) ElevationMatcher.VEHICLE_SPEED_SCALES else ElevationMatcher.DEFAULT_SCALES
        val match = elevation.match(car.route, car.s, search, scales) ?: return
        var target = match.s
        lastConfirmedTurnS(car)?.let { target = max(target, it) }
        nextHoldableTurn(car, settings())?.let { (_, turnS, _) -> if (turnS > car.s) target = min(target, max(turnS - 5.0, car.s)) }
        if (holdS > car.s) target = min(target, holdS)
        val detail = "rms=${"%.1f".format(Locale.US, match.rmsM)} relief=${match.reliefM.toInt()} ratio=${"%.1f".format(Locale.US, match.rivalRatio)} scale=${match.scale}"
        if (abs(target - car.s) >= TERRAIN_MIN_SHIFT_M) {
            log("terrain_snap from_s=${car.s.toInt()} to_s=${target.toInt()} $detail")
            car.moveTo(target)
            catchUp = 0.0
        } else {
            log("terrain_confirm s=${car.s.toInt()} $detail")
        }
        drDriftM = min(drDriftM, TERRAIN_DRIFT_AFTER_M)
    }

    // ------------------------------------------------------------------ network

    /** Project a new network/cell fix onto the route and let the [NetworkTracker] gate it. */
    private fun processNetwork(car: RouteCursor, fix: RawFix?, nowMs: Long) {
        if (fix == null || fix.elapsedMs == lastNetProcessedMs) return
        lastNetProcessedMs = fix.elapsedMs
        if (nowMs - fix.elapsedMs > 30_000) return
        val route = car.route
        val proj = routeProjector.project(route, fix.point, car.s, 250.0, 2500.0, 120.0)
        val acc = fix.accuracyM?.toDouble() ?: 500.0
        checkNetworkDeviation(car, proj, acc, fix, nowMs)

        when (net.gate(fix.elapsedMs, proj.s, acc)) {
            NetworkTracker.GateResult.REJECTED -> {
                if (nowMs - netRejectLogMs >= 10_000) {
                    netRejectLogMs = nowMs
                    log("net_reject s_net=${proj.s.toInt()} s_marker=${car.s.toInt()} acc=${acc.toInt()}")
                }
                return
            }

            NetworkTracker.GateResult.REANCHORED -> {
                net.clearSamples()
                log("net_reanchor s_net=${proj.s.toInt()} s_marker=${car.s.toInt()}")
            }

            NetworkTracker.GateResult.ACCEPTED -> Unit
        }
        net.record(NetSample(fix.elapsedMs, proj.s, acc, proj.offsetM), fix.lat, fix.lon)
    }

    /** Three network fixes in a row far from the route ⇒ offer a reroute. */
    private fun checkNetworkDeviation(car: RouteCursor, proj: Projection, acc: Double, fix: RawFix, nowMs: Long) {
        val config = settings()
        val gpsRecent = lastGpsUseMs > 0 && nowMs - lastGpsUseMs < 3000
        if (gpsRecent || rerouting || !config.blindDeviationEnabled) {
            netDevFastCount = 0
            netDevCount = 0
            netDevFastKey = null
            return
        }
        val remaining = car.route.length - car.s
        val off = proj.offsetM
        if (remaining >= 1000.0 && acc <= 300.0) {
            val key = "${fix.lat},${fix.lon}"
            if (key != netDevFastKey) {
                netDevFastKey = key
                if (off >= max(100.0, 30.0 + 2.5 * acc)) {
                    netDevFastCount++
                } else if (off <= acc + 30.0) {
                    netDevFastCount = 0
                }
                if (netDevFastCount >= 3) {
                    netDevFastCount = 0
                    netDevCount = 0
                    if (deviation.canOffer(nowMs)) {
                        offerDeviation(
                            nowMs,
                            "blind_deviation_net off=${off.toInt()} acc=${acc.toInt()} rule=fast delay_s=${config.blindDeviationDelayS}",
                            phrases.blindOffRoute(config.blindDeviationDelayS),
                        )
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
        netDevCount++
        if (netDevCount < 3) return
        netDevCount = 0
        if (deviation.canOffer(nowMs)) {
            offerDeviation(
                nowMs,
                "blind_deviation_net off=${off.toInt()} acc=${acc.toInt()} delay_s=${config.blindDeviationDelayS}",
                phrases.blindOffRoute(config.blindDeviationDelayS),
            )
        }
    }

    /**
     * Keep the marker within [s_net ± (acc + 300)] of the latest consistent network fix, and
     * when it lags, schedule a Kalman-style catch-up: gain = σ_dr² / (σ_dr² + (acc+30)²),
     * σ_dr = min(600, 25 + 0.08·distance dead-reckoned).
     */
    private fun bandCorrection(car: RouteCursor, fix: RawFix?, nowMs: Long, dt: Double, stopped: Boolean) {
        val latest = net.recent.lastOrNull() ?: return
        if (fix == null || latest.elapsedMs != fix.elapsedMs || nowMs - latest.elapsedMs >= 30_000) return
        if (!net.lastTwoConsistent()) return
        val band = latest.accM + 300.0
        val maxStep = MAX_SPEED_MPS * dt
        if (!stopped && car.s > latest.s + band) car.moveTo(max(latest.s + band, car.s - maxStep))
        if (car.s < latest.s - band) {
            var target = min(car.s + maxStep, latest.s - band)
            if (holdS > car.s) target = min(target, holdS)
            car.moveTo(target)
        }
        if (!stopped && latest.elapsedMs != lastCatchupFixMs) {
            lastCatchupFixMs = latest.elapsedMs
            val gap = latest.s - car.s
            if (latest.accM <= 100.0 && gap > 0) {
                // How much to trust the network fix vs. our own estimate, like a Kalman filter:
                // the longer we dead-reckoned (bigger sigmaDr), the more of the gap we close.
                val sigmaDr = min(600.0, 25.0 + drDriftM)
                val sigmaNet = latest.accM + 30.0
                val gain = gap * sigmaDr * sigmaDr / (sigmaDr * sigmaDr + sigmaNet * sigmaNet)
                if (gain > 1.0) catchUp = gain
            }
        }
    }

    /** Close the scheduled [catchUp] gradually (at most 15 m/s extra) instead of jumping. */
    private fun applyCatchUp(car: RouteCursor, dt: Double) {
        if (catchUp <= 0.0 || car.s >= holdS) return
        val step = minOf(catchUp, 15.0 * dt, holdS - car.s)
        car.advance(step)
        catchUp -= step
    }

    /** If the marker has run far ahead of every recent network fix, pull it back to their weighted mean. */
    private fun netBack(car: RouteCursor, nowMs: Long) {
        net.pruneHistory(nowMs)
        if (nowMs - lastNetBackMs < 30_000 || net.history.isEmpty()) return
        val usable = net.history.filter { nowMs - it.elapsedMs in 0..30_000 && it.accM <= 150.0 }
        if (usable.size < 3) return
        val predicted = usable.map { it.s + currentSpeed * (nowMs - it.elapsedMs) / 1000.0 }
        if (usable.indices.any { car.s - predicted[it] < max(200.0, usable[it].accM * 2.0) }) return
        val weights = usable.map { 1.0 / (it.accM * it.accM) }
        val mean = usable.indices.sumOf { weights[it] * predicted[it] } / weights.sum()
        var target = max(mean, car.s - 300.0)
        lastConfirmedTurnS(car)?.let { target = max(target, it) }
        if (target >= car.s) return
        lastNetBackMs = nowMs
        log("net_back from_s=${car.s.toInt()} to_s=${target.toInt()} n=${usable.size}")
        car.moveTo(target)
        catchUp = 0.0
    }

    /** Position just after the last turn we are sure we took; corrections never move the marker before it. */
    private fun lastConfirmedTurnS(car: RouteCursor): Double? = consumedSteps.map { car.route.stepS(it) + 20.0 }.filter { it <= car.s }.maxOrNull()

    // ------------------------------------------------------------------ deviation / reroute

    /** Start the "you left the route — rerouting in N s" countdown (needs a trusted point to reroute from). */
    private fun offerDeviation(nowMs: Long, logLine: String, speech: String) {
        val from = trustedPoint ?: return
        if (!deviation.canOffer(nowMs)) return
        deviationFrom = from
        deviation.pendingUntilMs = nowMs + settings().blindDeviationDelayS * 1000L
        log(logLine)
        listener.onAlert(NavAlert.OFF_ROUTE)
        say(speech, urgent = true)
    }

    private fun deviationTick(nowMs: Long) {
        if (!deviation.pending || nowMs < deviation.pendingUntilMs) return
        deviation.clear(nowMs, 90_000)
        log("blind_deviation_reroute auto=true")
        deviationFrom?.let { requestReroute(it, auto = true) }
    }

    /** Ask the app (via [NavListener]) for a new route from [from], keeping waypoints still ahead. */
    private fun requestReroute(from: GeoPoint, auto: Boolean) {
        val car = cursor ?: return
        val dest = destination ?: return
        if (rerouting) return
        rerouting = true
        val via = waypointS.filter { it.second > car.s + 30.0 }.map { it.first }
        log("reroute_from %.5f %.5f via=%d".format(Locale.US, from.lat, from.lon, via.size))
        publishFlags()
        listener.onRerouteRequested(from, dest, via, auto)
    }

    // ------------------------------------------------------------------ output

    /** Build the new [GuidanceState] for the UI (position, next maneuver, uncertainty…) and speak. */
    private fun publish(car: RouteCursor, pos: PositioningSnapshot, nowMs: Long) {
        val config = settings()
        val route = car.route
        val s = car.s
        val point = route.pointAt(s)
        val next = route.steps.indices.firstOrNull { route.steps[it].type != "depart" && route.stepS(it) > s + 8.0 } ?: -1
        val nextStep = route.steps.getOrNull(next)
        val distToNext = if (next >= 0) route.stepS(next) - s else 0.0
        val remaining = route.length - s
        val arrived = remaining < config.arriveM
        val blindS = ((nowMs - if (lastGpsUseMs > 0) lastGpsUseMs else navStartMs) / 1000).toInt()
        val netFresh = activeNavigationMethod == NavigationMethod.HYBRID && pos.lastNet?.let { nowMs - it.elapsedMs < 30_000 } == true
        val uncertainty = when {
            source.isGps -> 15.0

            source == PositionSource.CELL -> cellAccuracyM

            else -> {
                // Drift grows ~8 % of distance dead-reckoned (2 % with OBD-II speed) on top of the anchor's own
                // error: 25 m after a GPS fix, or the start position's accuracy if GPS has not been usable yet.
                val anchor = if (lastGpsUseMs > 0) 25.0 else max(25.0, startAccuracyM)
                val cap = max(if (netFresh) 350.0 else 600.0, anchor)
                max(30.0, min(cap, anchor + drDriftM))
            }
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
            travelMode = mode,
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

    /** Voice output: arrival, GPS lost/restored, and maneuvers at 1000 / 400 / 150 / 40 m. */
    private fun announce(next: Int, step: Step?, dist: Double, arrived: Boolean, blindS: Int) {
        if (arrived) {
            if (!arrivedAnnounced) {
                arrivedAnnounced = true
                listener.onAlert(NavAlert.ARRIVED)
                say(phrases.arrived(), urgent = false)
            }
            return
        }
        if (source.isGps && gpsLostAnnounced) {
            gpsLostAnnounced = false
            listener.onAlert(NavAlert.GPS_RESTORED)
            say(phrases.gpsRestored(), urgent = false)
        } else if (!source.isGps && source != PositionSource.NONE && lastGpsUseMs > 0 && !gpsLostAnnounced && blindS >= 5) {
            gpsLostAnnounced = true
            listener.onAlert(NavAlert.GPS_LOST)
            say(phrases.gpsLost(), urgent = false)
        }
        if (step == null || next < 0) return
        val announceAt = if (mode == TravelMode.FOOT) WALK_ANNOUNCE_AT_M else ANNOUNCE_AT_M
        val level = announceAt.indexOfLast { dist <= it }
        if (level < 0) return
        // Each (step, distance level) is announced once; key = step × 10 + level.
        val key = next * 10 + level
        if (key in announced) return
        for (skipped in 0..level) announced += next * 10 + skipped
        // "In 1 km…" is pointless in slow city traffic (below 50 km/h).
        if (mode == TravelMode.CAR && level == 0 && currentSpeed < 14.0) return
        when (level) {
            announceAt.lastIndex -> listener.onAlert(NavAlert.TURN_NOW)
            announceAt.lastIndex - 1 -> listener.onAlert(NavAlert.TURN_SOON)
        }
        say(phrases.maneuver(step, if (level == announceAt.lastIndex) null else dist), urgent = level >= 2)
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
        private const val MOTION_NETWORK_MAX_AGE_MS = 10_000L
        private const val MOTION_NETWORK_SIGMAS = 3.0
        private const val MOTION_NETWORK_MIN_SPEED_MPS = 1.0
        const val TICK_MS = 500L

        /** Driving: announce maneuvers this far ahead (the last one is "now"). */
        private val ANNOUNCE_AT_M = listOf(1000.0, 400.0, 150.0, 40.0)

        /** Walking: shorter distances. */
        private val WALK_ANNOUNCE_AT_M = listOf(150.0, 50.0, 15.0)

        /** Typical walking pace, used on phones without a step detector. */
        private const val WALKING_SPEED_MPS = 1.3

        /** A cell estimate older than this no longer counts as a current position. */
        private const val CELL_FIX_MAX_AGE_MS = 30_000L

        /** Conservative fallback when a cell fix did not report an accuracy radius. */
        private const val DEFAULT_CELL_ACCURACY_M = 5_000.0

        /** OBD-II speed older than this is not used (the adapter answers several times a second). */
        private const val VEHICLE_SPEED_MAX_AGE_MS = 2_500L
        private const val MAX_VEHICLE_KMH = 250.0

        /** Below this OBD speed the car is standing. */
        private const val VEHICLE_STOPPED_MPS = 0.5
        private const val SCALE_LEARN_MIN_MPS = 5.0

        /** Dead-reckoning drift as a fraction of the distance driven. */
        private const val ESTIMATED_SPEED_DRIFT = 0.08
        private const val VEHICLE_SPEED_DRIFT = 0.02

        /** Terrain matching: how often to try, how far to search, what counts as a correction. */
        private const val TERRAIN_EVERY_MS = 5_000L
        private const val TERRAIN_MIN_SEARCH_M = 150.0
        private const val TERRAIN_MAX_SEARCH_M = 1_000.0
        private const val TERRAIN_MIN_SHIFT_M = 20.0

        /** After a terrain fix the position is known to about this, metres (DEM resolution + fit). */
        private const val TERRAIN_DRIFT_AFTER_M = 40.0
    }
}
