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
    private val nativeEstimator: RouteEstimateProvider? = null,
) {
    val motion = MotionDetector(tuning).also { it.log = ::log }
    private val comparisonTurns = TurnDetector()

    /** Walking speed from the step detector (used in [TravelMode.FOOT]). */
    val pedometer = Pedometer()

    /** Car or on foot; set by [start]. */
    var mode: TravelMode = TravelMode.CAR
        private set

    /** Latched at trip start: changing the preference cannot change the active state owner. */
    var estimator: NavigationEstimator = NavigationEstimator.KOTLIN
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
    private val networkDeviation = NetworkDeviationDetector()
    private val networkCorrection = NetworkCorrection(net, ::log)

    // Turn matching owns its state and resets it whenever the route changes.
    private val turnProgress = TurnProgress(motion, net, ::settings, { phrases }, ::log, ::offerDeviation) { networkCorrection.clearCatchUp() }

    // Compass / signals
    private var compassMismatchSinceMs = 0L
    private var lastCompassSnapMs = -30_000L
    private val usedSignals = HashSet<Int>()

    // Deviation offers
    private val deviation = DeviationOffer()
    private var deviationFrom: GeoPoint? = null

    // Announcements
    private val announcer = GuidanceAnnouncer(listener, ::say)

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
        mode: TravelMode = TravelMode.CAR,
        estimator: NavigationEstimator = NavigationEstimator.KOTLIN,
    ) {
        this.mode = mode
        this.estimator = estimator
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
        announcer.resetTrip()
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
        state = state.copy(s = car.s, position = car.route.pointAt(car.s).point, uncertaintyM = startAccuracyM)
        // Turns already behind the saved position were driven.
        turnProgress.markPassed(car.route, s)
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
        if (kmh !in 0.0..MAX_VEHICLE_KMH || elapsedMs < 0 || elapsedMs <= vehicleSpeedAtMs) return
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
        val car = cursor ?: return null
        if (mode == TravelMode.FOOT) return walkingEvidence(pedometer, motion, nowMs)
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
        usedSignals.clear()
        announcer.resetRoute()
        net.reset()
        lastCellProcessedMs = -1L
        motionHistory.clear()
        turnProgress.reset()
        networkCorrection.clearCatchUp()
        rerouting = false
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
        // A repeated or delayed callback must not rewind the integration clock or publish twice.
        if (nowMs < navStartMs || nowMs <= lastTickMs) return
        val dt = if (lastTickMs < 0) 0.0 else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 5.0)
        lastTickMs = nowMs
        recovery.update(pos.jammed, pos.gpsState == GpsState.LOST || simulateGpsLoss, nowMs)

        if (estimator == NavigationEstimator.NATIVE_KALMAN) {
            tickNative(car, pos, nowMs, dt)
            return
        }

        updateNavigationMethod()
        if (activeNavigationMethod == NavigationMethod.HYBRID) {
            processNetwork(car, pos.lastNet, nowMs)
            if (lastGpsUseMs == 0L || nowMs - lastGpsUseMs >= 3000) networkCorrection.netBack(car, nowMs, currentSpeed, lastConfirmedTurnS(car))
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

    /** Native owns position, speed and uncertainty; Kotlin retains guidance and deviation checks only. */
    private fun tickNative(car: RouteCursor, pos: PositioningSnapshot, nowMs: Long, dt: Double) {
        processNetwork(car, pos.lastNet, nowMs)
        val input = if (simulateGpsLoss || pos.lastUsableGps?.verdict?.level == TrustLevel.BAD) pos.copy(lastUsableGps = null) else pos
        val estimate = nativeEstimator?.estimate(nowMs, input, motionEvidence(nowMs), turnEvidence(nowMs))?.takeIf { it.valid }
        if (estimate == null) {
            // Hold during asynchronous restore. Never silently switch algorithms or invent movement.
            currentSpeed = 0.0
            source = PositionSource.NONE
            publish(car, pos, nowMs, max(startAccuracyM, state.uncertaintyM) + MAX_SPEED_MPS * dt, announceGuidance = false)
            return
        }
        car.moveTo(estimate.positionM)
        currentSpeed = estimate.speedMps
        updateNativeGps(car, input.lastUsableGps, estimate, nowMs)
        if (simulateGpsLoss || lastGpsUseMs == 0L || nowMs - lastGpsUseMs >= 3000) {
            source = when {
                currentSpeed < VEHICLE_STOPPED_MPS -> PositionSource.DR_STOPPED
                freshVehicleSpeed(nowMs) != null -> PositionSource.DR_OBD
                else -> PositionSource.DR
            }
        }
        odometerM += currentSpeed * dt
        elevation.onTravel(odometerM)
        deviationTick(nowMs)
        publish(car, pos, nowMs, estimate.safetyRadiusM)
    }

    /** Keep raw GOOD-GPS off-route detection even when the route-constrained filter rejects its projection. */
    private fun updateNativeGps(car: RouteCursor, gps: JudgedFix?, estimate: RouteEstimate, nowMs: Long) {
        val fix = gps?.fix ?: return
        if (nowMs - fix.elapsedMs !in 0..NATIVE_GPS_MAX_AGE_MS) return
        if (fix.elapsedMs <= lastGpsProcessedMs) return
        lastGpsProcessedMs = fix.elapsedMs
        val good = gps.verdict.level == TrustLevel.GOOD
        recovery.onFix(good)
        val projection = routeProjector.project(car.route, fix.point, car.s, 250.0, 2500.0, 120.0)
        if (good) {
            trustedPoint = fix.point
            val courseDiff = fix.bearingDeg?.takeIf { (fix.speedMps ?: 0f) >= 15.0 / 3.6 }?.let {
                Geo.absAngleDiff(it.toDouble(), car.route.bearingAt(min(projection.s + 25.0, car.route.length)))
            }
            checkOffRoute(car, projection, courseDiff, fix, nowMs)
        }
        if (!estimate.gpsPositionAccepted) return
        lastGpsUseMs = fix.elapsedMs
        lastGpsSpeed = fix.speedMps?.toDouble()?.takeIf { it in 0.0..MAX_SPEED_MPS }
        if (mode == TravelMode.FOOT && good && estimate.gpsSpeedAccepted && nowMs - fix.elapsedMs <= TICK_MS) lastGpsSpeed?.let { pedometer.learnStride(it, nowMs) }
        if (deviation.pending) deviation.clear(nowMs, 90_000)
        source = if (good) PositionSource.GPS else PositionSource.GPS_SUSPECT
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
        networkCorrection.clearCatchUp()
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
        networkCorrection.clearCatchUp()
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

        if (speedMps > 0.3) {
            drDriftM += speedMps * dt * driftPerMetre()
            turnProgress.advance(car, speedMps, dt, nowMs)
        }
        if (mode == TravelMode.CAR) {
            // These corrections need a phone fixed in a car holder; a phone in the hand swings around.
            turnProgress.confirmHeldTurn(car, nowMs)
            turnProgress.matchGyroTurn(car, nowMs)
            checkBlindUturn(car, nowMs)
            compassSnap(car, pos.compassDeg, speedMps, nowMs)
        }

        if (config.signalSnap && stopped && !wasStopped) signalSnap(car)
        wasStopped = stopped
        networkCorrection.bandCorrection(car, networkFix, nowMs, dt, stopped, drDriftM, turnProgress.holdS)

        source = if (stopped) {
            PositionSource.DR_STOPPED
        } else {
            networkCorrection.applyCatchUp(car, dt, turnProgress.holdS)
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
            networkCorrection.clearCatchUp()
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
        val high = minOf(car.s + if (netHint != null) 300.0 else 150.0, route.length, turnProgress.holdS)
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
        networkCorrection.clearCatchUp()
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
        networkCorrection.clearCatchUp()
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
        turnProgress.nextHoldableTurn(car, settings())?.let { (_, turnS, _) -> if (turnS > car.s) target = min(target, max(turnS - 5.0, car.s)) }
        if (turnProgress.holdS > car.s) target = min(target, turnProgress.holdS)
        val detail = "rms=${"%.1f".format(Locale.US, match.rmsM)} relief=${match.reliefM.toInt()} ratio=${"%.1f".format(Locale.US, match.rivalRatio)} scale=${match.scale}"
        if (abs(target - car.s) >= TERRAIN_MIN_SHIFT_M) {
            log("terrain_snap from_s=${car.s.toInt()} to_s=${target.toInt()} $detail")
            car.moveTo(target)
            networkCorrection.clearCatchUp()
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
        val trigger = networkDeviation.check(car, proj, acc, fix, gpsRecent, rerouting, config) ?: return
        if (deviation.canOffer(nowMs)) {
            val rule = if (trigger.fast) " rule=fast" else ""
            offerDeviation(
                nowMs,
                "blind_deviation_net off=${trigger.offsetM.toInt()} acc=${trigger.accuracyM.toInt()}$rule delay_s=${config.blindDeviationDelayS}",
                phrases.blindOffRoute(config.blindDeviationDelayS),
            )
        }
    }

    /** Position just after the last turn we are sure we took; corrections never move the marker before it. */
    private fun lastConfirmedTurnS(car: RouteCursor): Double? = turnProgress.lastConfirmedTurnS(car)

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
    private fun publish(car: RouteCursor, pos: PositioningSnapshot, nowMs: Long, nativeUncertaintyM: Double? = null, announceGuidance: Boolean = true) {
        val config = settings()
        val progress = state.withRouteProgress(car, config.arriveM, announceGuidance)
        val blindS = ((nowMs - if (lastGpsUseMs > 0) lastGpsUseMs else navStartMs) / 1000).toInt()
        val netFresh = activeNavigationMethod == NavigationMethod.HYBRID && pos.lastNet?.let { nowMs - it.elapsedMs < 30_000 } == true
        val uncertainty = nativeUncertaintyM ?: kotlinUncertainty(source, cellAccuracyM, lastGpsUseMs > 0, startAccuracyM, netFresh, drDriftM)

        val nextState = progress.copy(
            speedKmh = (currentSpeed * 3.6).toFloat(),
            travelMode = mode,
            offRoute = offRouteDeclared,
            offRouteM = offRouteM,
            source = source,
            rerouting = rerouting,
            destination = destination,
            blindS = blindS,
            uncertaintyM = uncertainty,
            jamRecovering = recovery.active(nowMs),
            blindDeviation = deviation.pending,
            blindDeviationSecLeft = if (deviation.pending) max(0L, (deviation.pendingUntilMs - nowMs) / 1000).toInt() else 0,
        )
        if (announceGuidance) announcer.announce(nextState, phrases, currentSpeed, lastGpsUseMs > 0)
        state = nextState
    }

    private fun publishFlags() {
        state = state.copy(rerouting = rerouting, blindDeviation = deviation.pending)
    }

    private fun say(text: String, urgent: Boolean) {
        log("say $text")
        listener.onSay(text, urgent)
    }

    private fun log(message: String) = listener.onLog(message)

    companion object {
        private const val NATIVE_GPS_MAX_AGE_MS = 5_000L
        private const val MOTION_NETWORK_MAX_AGE_MS = 10_000L
        private const val MOTION_NETWORK_SIGMAS = 3.0
        private const val MOTION_NETWORK_MIN_SPEED_MPS = 1.0
        const val TICK_MS = 500L

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
