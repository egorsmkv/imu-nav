package org.blinddriver.core.gnss

import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.ServiceArea
import org.blinddriver.core.imu.GyroBiasEstimator

/** Everything the navigation engine needs to know about positioning at one instant. */
data class PositioningSnapshot(
    /** Most recent GPS fix that was not BAD, with its verdict. */
    val lastUsableGps: JudgedFix?,
    /** Most recent GOOD GPS fix (reroute anchor). */
    val lastGoodGps: RawFix?,
    val lastNet: RawFix?,
    val gpsState: GpsState,
    val jammed: Boolean,
    val compassDeg: Float?,
)

/**
 * Collects fixes from all providers plus receiver health, classifies GPS fixes and tracks the
 * overall GPS state (OK ≤ 5 s since a GOOD fix, LOST > 30 s, DEGRADED in between).
 */
class PositioningHub(
    trustConfig: TrustConfig = TrustConfig(),
    area: ServiceArea = ServiceArea.EVERYWHERE,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val classifier = TrustClassifier(trustConfig, area)
    private val jamDetector = JamDetector()
    val gyroBias = GyroBiasEstimator()

    var gnss = GnssSnapshot()
        private set
    var lastGood: RawFix? = null
        private set
    var lastUsable: JudgedFix? = null
        private set
    var lastJudged: JudgedFix? = null
        private set
    var lastNet: RawFix? = null
        private set
    var lastFused: RawFix? = null
        private set
    var compassDeg: Float? = null
        private set
    var gpsState = GpsState.LOST
        private set
    val jammed: Boolean get() = jamDetector.jammed

    /** Odometer over GOOD fixes (steps < 5 m ignored), metres. */
    var goodOdometerM = 0.0
        private set

    var log: ((String) -> Unit)? = null
    private var jamEndedAtMs = -1L

    fun onFix(fix: RawFix): Verdict? {
        when (fix.source) {
            FixSource.NET -> {
                lastNet = fix
                return null
            }
            FixSource.FUSED -> {
                lastFused = fix
                return null
            }
            FixSource.GPS -> Unit
        }
        val verdict = classifier.evaluate(fix, lastGood, lastNet, gnss, jammed, compassDeg, wallClock())
        val judged = JudgedFix(fix, verdict)
        lastJudged = judged
        gyroBias.onGpsSpeed(fix.elapsedMs, fix.speedMps)
        if (verdict.level != TrustLevel.BAD) lastUsable = judged
        if (verdict.level == TrustLevel.GOOD) {
            if (jamEndedAtMs > 0) {
                log?.invoke("first_good_after_jam dt_s=${(fix.elapsedMs - jamEndedAtMs) / 1000}")
                jamEndedAtMs = -1L
            }
            lastGood?.let { prev ->
                val step = Geo.distance(prev.lat, prev.lon, fix.lat, fix.lon)
                if (step >= 5.0) goodOdometerM += step
            }
            lastGood = fix
        }
        updateGpsState(fix.elapsedMs)
        return verdict
    }

    fun onGnssStatus(
        visible: Int,
        used: Int,
        meanCn0Used: Float?,
        cn0SpreadUsed: Float?,
        meanCn0Visible: Float?,
        dualFrequencyUsed: Int,
        elapsedMs: Long,
    ) {
        gnss = gnss.copy(
            satellitesVisible = visible,
            satellitesUsed = used,
            meanCn0Used = meanCn0Used,
            cn0SpreadUsed = cn0SpreadUsed,
            meanCn0Visible = meanCn0Visible,
            dualFrequencyUsed = dualFrequencyUsed,
            elapsedMs = elapsedMs,
        )
    }

    /** @return true when jamming just ended (caller may re-inject assisted-GPS data). */
    fun onAgc(agcDb: Float?, elapsedMs: Long): Boolean {
        gnss = gnss.copy(agcDb = agcDb)
        val wasJammed = jammed
        if (jamDetector.update(agcDb, elapsedMs)) {
            log?.invoke("jammed=$jammed agc=${agcDb?.let { "%.1f".format(it) }}")
            if (wasJammed && !jammed) {
                jamEndedAtMs = elapsedMs
                return true
            }
        }
        return false
    }

    fun onOrientation(headingDeg: Float?, yawRateDegS: Float?, elapsedMs: Long) {
        compassDeg = headingDeg
        yawRateDegS?.let { gyroBias.addYawRate(elapsedMs, it.toDouble()) }
    }

    fun updateGpsState(nowMs: Long): GpsState {
        val goodAge = lastGood?.let { nowMs - it.elapsedMs } ?: Long.MAX_VALUE
        val anyAge = lastJudged?.let { nowMs - it.fix.elapsedMs } ?: Long.MAX_VALUE
        val state = when {
            goodAge <= 5_000 -> GpsState.OK
            goodAge > 30_000 && (anyAge > 5_000 || lastJudged?.verdict?.level != TrustLevel.SUSPECT) -> GpsState.LOST
            else -> GpsState.DEGRADED
        }
        if (state != gpsState) log?.invoke("gps_state=$state")
        gpsState = state
        return state
    }

    fun snapshot(nowMs: Long): PositioningSnapshot {
        updateGpsState(nowMs)
        return PositioningSnapshot(lastUsable, lastGood, lastNet, gpsState, jammed, compassDeg)
    }
}
