package org.imunav.core

/**
 * All tunable thresholds of the dead-reckoning engine. The defaults are the factory preset.
 * Times are in milliseconds unless the name says otherwise.
 *
 * "Dead reckoning" (DR) = estimating where the car is without GPS, from speed, time and the
 * route. See `NavigationEngine` for how these values are used; the `replay` tool shows the
 * effect of a change on recorded trips (`--set key=value`).
 */
data class Tuning(
    // --- Stop / resume detector: is the car standing still? (accelerometer + gyro "quietness")
    /** Look at this much recent sensor data. */
    val stopWindowMs: Long = 1000,
    /** Must be quiet this long before we call it a stop. */
    val stopHoldMs: Long = 1500,
    /** Quiet = mean acceleration below this (m/s², gravity removed)… */
    val stopAccMean: Double = 0.25,
    /** …and its variation (standard deviation) below this. */
    val stopAccStd: Double = 0.3,
    /** Alternatively quiet = gyro below this (rad/s) while acceleration mean stays under [stopGyroAccMeanMax]. */
    val stopGyro: Double = 0.03,
    val stopGyroAccMeanMax: Double = 0.5,
    /** Moving again = acceleration mean or variation above these, or gyro above [resumeGyro]… */
    val resumeAccMean: Double = 0.5,
    val resumeAccStd: Double = 0.5,
    val resumeGyro: Double = 0.1,
    /** …for this long (filters out doors closing, people moving in the car). */
    val resumeConfirmMs: Long = 700,
    /** After a stop the car accelerates: estimated speed ramps from 15 % to 100 % over this time. */
    val resumeRampMs: Long = 8000,
    /** During the first part of the ramp, speed is capped at [resumeSlowCap] × cruising speed. */
    val resumeSlowMs: Long = 3000,
    val resumeSlowCap: Double = 0.6,

    // --- Gyro turn matching: recognise route turns from the gyroscope
    /** Route turns sharper than this (degrees) count as "real" turns. */
    val turnMinDeg: Double = 35.0,
    /** A measured turn may differ from the route's turn angle by this much and still match. */
    val turnTolDeg: Double = 25.0,
    /** Search route turns from this far behind the marker… */
    val turnBehindM: Double = 400.0,
    /** …to this far ahead of it. */
    val turnAheadM: Double = 300.0,
    /** Integrate the gyro over this window to measure a turn. */
    val turnWindowMs: Long = 6000,
    /** Park the marker just before the next turn until the turn is confirmed ("turn hold"). */
    val turnHoldEnabled: Boolean = true,
    /** Give up holding after this many seconds of driving. */
    val turnHoldMaxS: Int = 25,

    // --- Off-route detection and arrival (with GPS)
    /** GPS further than this from the route… */
    val offRouteM: Double = 50.0,
    /** …for this long ⇒ off route. */
    val offRouteHoldMs: Long = 8000,
    /** Faster rule: further than [offRouteFastM] AND heading off by [offRouteFastDeg] for [offRouteFastHoldMs]. */
    val offRouteFastM: Double = 30.0,
    val offRouteFastDeg: Double = 40.0,
    val offRouteFastHoldMs: Long = 3000,
    /** "You have arrived" within this distance of the destination. */
    val arriveM: Double = 30.0,

    // --- Map knowledge
    /** Slow the estimated speed down near traffic signals / speed bumps. */
    val speedPlan: Boolean = true,
    /** When the car stops close to a traffic signal, snap the marker to it. */
    val signalSnap: Boolean = true,
    /** Match the barometer's height changes against the route's elevation profile (packs with elevation). */
    val terrainMatch: Boolean = true,

    // --- Leaving the route without GPS ("blind deviation")
    /** Offer a reroute when the sensors say we left the route. */
    val blindDeviationEnabled: Boolean = true,
    /** A U-turn = the gyro turned at least this many degrees where the route goes straight. */
    val blindDeviationMinDeg: Double = 90.0,
    /** Seconds the voice countdown gives the driver to cancel before rerouting. */
    val blindDeviationDelayS: Int = 8,
    /** Also treat "drove past a held turn without turning" as leaving the route. */
    val missedTurnEnabled: Boolean = false,
    /** …after driving this far past it. */
    val missedTurnM: Double = 250.0,

    // --- Speed cameras (not implemented yet; kept so saved presets stay compatible)
    val cameraShowM: Double = 2000.0,
    val cameraWarnM: Double = 700.0,
    val cameraBeepM: Double = 300.0,
    val cameraSpeedTolKmh: Double = 5.0,
) {
    /**
     * The same settings adapted for walking: tighter off-route and arrival distances, and the
     * car-only corrections switched off (turn hold and U-turn detection rely on a phone fixed in
     * a car holder; traffic-signal and speed-bump rules are about cars).
     */
    fun forWalking(): Tuning = copy(
        turnHoldEnabled = false,
        speedPlan = false,
        signalSnap = false,
        blindDeviationEnabled = false,
        missedTurnEnabled = false,
        offRouteM = 30.0,
        offRouteHoldMs = 10_000,
        offRouteFastM = 20.0,
        offRouteFastDeg = 60.0,
        offRouteFastHoldMs = 5_000,
        arriveM = 15.0,
    )

    /** Clamp every numeric parameter into its documented range and keep camera distances ordered. */
    fun sanitized(): Tuning {
        val clamped = SPECS.fold(this) { tuning, spec -> spec.set(tuning, spec.get(tuning).coerceIn(spec.min, spec.max)) }
        val warn = minOf(clamped.cameraWarnM, clamped.cameraShowM)
        return clamped.copy(cameraWarnM = warn, cameraBeepM = minOf(clamped.cameraBeepM, warn))
    }

    /**
     * Describes one numeric parameter for a settings UI / the replay tool: its [key], allowed
     * range and step, and how to read ([get]) and change ([set]) it on a [Tuning].
     */
    class Spec(
        val key: String,
        val group: String,
        val unit: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val get: (Tuning) -> Double,
        val set: (Tuning, Double) -> Tuning,
    )

    companion object {
        /** Older modem measurements can describe a different stretch of road, even in a new callback. */
        const val CELL_MAX_AGE_MS = 10_000L

        val DEFAULT = Tuning()

        val SPECS: List<Spec> = listOf(
            Spec("stopWindowMs", "stop", "ms", 500.0, 3000.0, 100.0, { it.stopWindowMs.toDouble() }, { t, v -> t.copy(stopWindowMs = v.toLong()) }),
            Spec("stopHoldMs", "stop", "ms", 500.0, 6000.0, 100.0, { it.stopHoldMs.toDouble() }, { t, v -> t.copy(stopHoldMs = v.toLong()) }),
            Spec("stopAccMean", "stop", "m/s²", 0.05, 1.0, 0.01, { it.stopAccMean }, { t, v -> t.copy(stopAccMean = v) }),
            Spec("stopAccStd", "stop", "m/s²", 0.05, 1.0, 0.01, { it.stopAccStd }, { t, v -> t.copy(stopAccStd = v) }),
            Spec("stopGyro", "stop", "rad/s", 0.005, 0.2, 0.005, { it.stopGyro }, { t, v -> t.copy(stopGyro = v) }),
            Spec("stopGyroAccMeanMax", "stop", "m/s²", 0.1, 1.5, 0.05, { it.stopGyroAccMeanMax }, { t, v -> t.copy(stopGyroAccMeanMax = v) }),
            Spec("resumeAccMean", "stop", "m/s²", 0.1, 2.0, 0.05, { it.resumeAccMean }, { t, v -> t.copy(resumeAccMean = v) }),
            Spec("resumeAccStd", "stop", "m/s²", 0.1, 2.0, 0.05, { it.resumeAccStd }, { t, v -> t.copy(resumeAccStd = v) }),
            Spec("resumeGyro", "stop", "rad/s", 0.02, 0.5, 0.01, { it.resumeGyro }, { t, v -> t.copy(resumeGyro = v) }),
            Spec("resumeConfirmMs", "stop", "ms", 200.0, 3000.0, 100.0, { it.resumeConfirmMs.toDouble() }, { t, v -> t.copy(resumeConfirmMs = v.toLong()) }),
            Spec("resumeRampMs", "stop", "ms", 1000.0, 20000.0, 500.0, { it.resumeRampMs.toDouble() }, { t, v -> t.copy(resumeRampMs = v.toLong()) }),
            Spec("resumeSlowMs", "stop", "ms", 0.0, 10000.0, 500.0, { it.resumeSlowMs.toDouble() }, { t, v -> t.copy(resumeSlowMs = v.toLong()) }),
            Spec("resumeSlowCap", "stop", "×", 0.15, 1.0, 0.05, { it.resumeSlowCap }, { t, v -> t.copy(resumeSlowCap = v) }),
            Spec("turnMinDeg", "turn", "°", 15.0, 90.0, 1.0, { it.turnMinDeg }, { t, v -> t.copy(turnMinDeg = v) }),
            Spec("turnTolDeg", "turn", "°", 10.0, 60.0, 1.0, { it.turnTolDeg }, { t, v -> t.copy(turnTolDeg = v) }),
            Spec("turnBehindM", "turn", "m", 100.0, 800.0, 50.0, { it.turnBehindM }, { t, v -> t.copy(turnBehindM = v) }),
            Spec("turnAheadM", "turn", "m", 100.0, 600.0, 50.0, { it.turnAheadM }, { t, v -> t.copy(turnAheadM = v) }),
            Spec("turnWindowMs", "turn", "ms", 2000.0, 15000.0, 500.0, { it.turnWindowMs.toDouble() }, { t, v -> t.copy(turnWindowMs = v.toLong()) }),
            Spec("turnHoldMaxS", "turn", "s", 5.0, 60.0, 5.0, { it.turnHoldMaxS.toDouble() }, { t, v -> t.copy(turnHoldMaxS = v.toInt()) }),
            Spec("offRouteM", "route", "m", 20.0, 150.0, 5.0, { it.offRouteM }, { t, v -> t.copy(offRouteM = v) }),
            Spec("offRouteHoldMs", "route", "ms", 2000.0, 30000.0, 1000.0, { it.offRouteHoldMs.toDouble() }, { t, v -> t.copy(offRouteHoldMs = v.toLong()) }),
            Spec("offRouteFastM", "route", "m", 15.0, 100.0, 5.0, { it.offRouteFastM }, { t, v -> t.copy(offRouteFastM = v) }),
            Spec("offRouteFastDeg", "route", "°", 20.0, 90.0, 5.0, { it.offRouteFastDeg }, { t, v -> t.copy(offRouteFastDeg = v) }),
            Spec("offRouteFastHoldMs", "route", "ms", 1000.0, 10000.0, 500.0, { it.offRouteFastHoldMs.toDouble() }, { t, v -> t.copy(offRouteFastHoldMs = v.toLong()) }),
            Spec("arriveM", "route", "m", 10.0, 100.0, 5.0, { it.arriveM }, { t, v -> t.copy(arriveM = v) }),
            Spec("blindDeviationMinDeg", "route", "°", 60.0, 170.0, 5.0, { it.blindDeviationMinDeg }, { t, v -> t.copy(blindDeviationMinDeg = v) }),
            Spec("missedTurnM", "route", "m", 50.0, 500.0, 10.0, { it.missedTurnM }, { t, v -> t.copy(missedTurnM = v) }),
            Spec("blindDeviationDelayS", "route", "s", 3.0, 20.0, 1.0, { it.blindDeviationDelayS.toDouble() }, { t, v -> t.copy(blindDeviationDelayS = v.toInt()) }),
            Spec("cameraShowM", "camera", "m", 500.0, 4000.0, 100.0, { it.cameraShowM }, { t, v -> t.copy(cameraShowM = v) }),
            Spec("cameraWarnM", "camera", "m", 200.0, 1500.0, 50.0, { it.cameraWarnM }, { t, v -> t.copy(cameraWarnM = v) }),
            Spec("cameraBeepM", "camera", "m", 100.0, 800.0, 50.0, { it.cameraBeepM }, { t, v -> t.copy(cameraBeepM = v) }),
            Spec("cameraSpeedTolKmh", "camera", "km/h", 0.0, 20.0, 1.0, { it.cameraSpeedTolKmh }, { t, v -> t.copy(cameraSpeedTolKmh = v) }),
        )
    }
}
