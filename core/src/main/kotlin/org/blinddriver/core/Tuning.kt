package org.blinddriver.core

/**
 * All user-tunable thresholds of the dead-reckoning engine. Defaults are the factory preset.
 * Times are in milliseconds unless the name says otherwise.
 */
data class Tuning(
    // --- Stop / resume detector (accelerometer + gyro "quietness") ---
    val stopWindowMs: Long = 1000,
    val stopHoldMs: Long = 1500,
    val stopAccMean: Double = 0.25,
    val stopAccStd: Double = 0.3,
    val stopGyro: Double = 0.03,
    val stopGyroAccMeanMax: Double = 0.5,
    val resumeAccMean: Double = 0.5,
    val resumeAccStd: Double = 0.5,
    val resumeGyro: Double = 0.1,
    val resumeConfirmMs: Long = 700,
    val resumeRampMs: Long = 8000,
    val resumeSlowMs: Long = 3000,
    val resumeSlowCap: Double = 0.6,

    // --- Gyro turn matching ---
    val turnMinDeg: Double = 35.0,
    val turnTolDeg: Double = 25.0,
    val turnBehindM: Double = 400.0,
    val turnAheadM: Double = 300.0,
    val turnWindowMs: Long = 6000,
    val turnHoldEnabled: Boolean = true,
    val turnHoldMaxS: Int = 25,

    // --- Off-route / arrival ---
    val offRouteM: Double = 50.0,
    val offRouteHoldMs: Long = 8000,
    val offRouteFastM: Double = 30.0,
    val offRouteFastDeg: Double = 40.0,
    val offRouteFastHoldMs: Long = 3000,
    val arriveM: Double = 30.0,

    // --- Map knowledge ---
    val speedPlan: Boolean = true,
    val signalSnap: Boolean = true,

    // --- Deviation without GPS ---
    val blindDeviationEnabled: Boolean = true,
    val blindDeviationMinDeg: Double = 90.0,
    val blindDeviationDelayS: Int = 8,
    val missedTurnEnabled: Boolean = false,
    val missedTurnM: Double = 250.0,

    // --- Speed cameras ---
    val cameraShowM: Double = 2000.0,
    val cameraWarnM: Double = 700.0,
    val cameraBeepM: Double = 300.0,
    val cameraSpeedTolKmh: Double = 5.0,
) {
    /** Clamp every numeric parameter into its documented range and keep camera distances ordered. */
    fun sanitized(): Tuning {
        var t = this
        for (spec in SPECS) t = spec.set(t, spec.get(t).coerceIn(spec.min, spec.max))
        val warn = minOf(t.cameraWarnM, t.cameraShowM)
        return t.copy(cameraWarnM = warn, cameraBeepM = minOf(t.cameraBeepM, warn))
    }

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
