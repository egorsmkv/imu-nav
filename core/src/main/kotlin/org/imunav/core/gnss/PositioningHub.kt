package org.imunav.core.gnss

import org.imunav.core.geo.Geo
import org.imunav.core.geo.ServiceArea
import org.imunav.core.imu.GyroBiasEstimator
import org.imunav.core.imu.eskf.InertialSample
import org.imunav.core.record.TripEvent
import java.util.Locale

/** Everything the navigation engine needs to know about positioning at one instant. */
data class PositioningSnapshot(
    /** Most recent GPS fix that was not BAD, with its verdict. */
    val lastUsableGps: JudgedFix?,
    /** Most recent GOOD GPS fix (reroute anchor). */
    val lastGoodGps: RawFix?,
    val lastNet: RawFix?,
    /** Most recent fix computed locally from the offline cell-tower database. */
    val lastCell: RawFix?,
    val gpsState: GpsState,
    val jammed: Boolean,
    val compassDeg: Float?,
)

/**
 * The single place all positioning inputs arrive: fixes from every provider, satellite status,
 * AGC and orientation. It
 *  - judges every GPS fix with the [TrustClassifier] (GOOD / SUSPECT / BAD),
 *  - remembers the latest fix of each kind,
 *  - tracks the overall GPS state: OK up to 5 s after a GOOD fix, LOST after 30 s, DEGRADED in between,
 *  - forwards every input to [recorder] so trips can be replayed later.
 *
 * Android code only feeds it (see `SensorHub`); the navigation engine reads a [snapshot].
 */
class PositioningHub(
    trustConfig: TrustConfig = TrustConfig(),
    area: ServiceArea = ServiceArea.EVERYWHERE,
    private val wallClock: () -> Long = System::currentTimeMillis,
    trustEvaluator: TrustEvaluator? = null,
    jammingDetector: JammingDetector? = null,
) {
    private val classifier = trustEvaluator ?: TrustClassifier(trustConfig, area)
    private val jamDetector = jammingDetector ?: JamDetector()
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
    var lastCell: RawFix? = null
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

    /** Receives every raw input (fixes, satellite status, AGC) — used to record trips for replay. */
    var recorder: ((TripEvent) -> Unit)? = null

    /** Optional shadow-only observers; neither can replace a trust decision or navigation position. */
    var inertialObserver: ((InertialSample) -> Unit)? = null
    var judgedFixObserver: ((JudgedFix) -> Unit)? = null
    private var jamEndedAtMs = -1L

    /** Record raw sensor timing before forwarding it to the experimental inertial estimator. */
    fun onInertial(sample: InertialSample, arrivalMs: Long) {
        recorder?.invoke(TripEvent.Inertial(arrivalMs, sample))
        inertialObserver?.invoke(sample)
    }

    /**
     * A new location fix from any provider. Only GPS fixes are judged; for them the verdict is
     * returned (null for other sources).
     */
    fun onFix(fix: RawFix): Verdict? {
        recorder?.invoke(TripEvent.Fix(fix))
        when (fix.source) {
            FixSource.NET -> {
                lastNet = fix
                return null
            }

            FixSource.FUSED -> {
                lastFused = fix
                return null
            }

            FixSource.CELL -> {
                lastCell = fix
                if (cellReplacesNetwork(fix, lastNet)) lastNet = fix
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
        judgedFixObserver?.invoke(judged)
        return verdict
    }

    /**
     * Our own offline cell fix stands in for Android's network location, unless Android has a
     * fresher (≤ 10 s old) and more accurate network fix.
     */
    private fun cellReplacesNetwork(cell: RawFix, net: RawFix?): Boolean {
        if (net == null || net.source == FixSource.CELL) return true
        val netIsStale = cell.elapsedMs - net.elapsedMs > 10_000
        val cellIsMoreAccurate = (cell.accuracyM ?: Float.MAX_VALUE) < (net.accuracyM ?: Float.MAX_VALUE)
        return netIsStale || cellIsMoreAccurate
    }

    /** Satellite status (from Android's GnssStatus callback). */
    fun onGnssStatus(visible: Int, used: Int, meanCn0Used: Float?, cn0SpreadUsed: Float?, meanCn0Visible: Float?, dualFrequencyUsed: Int, elapsedMs: Long) {
        recorder?.invoke(
            TripEvent.Gnss(elapsedMs, visible, used, meanCn0Used, cn0SpreadUsed, meanCn0Visible, dualFrequencyUsed),
        )
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
        recorder?.invoke(TripEvent.Agc(elapsedMs, agcDb))
        gnss = gnss.copy(agcDb = agcDb)
        val wasJammed = jammed
        if (jamDetector.update(agcDb, elapsedMs)) {
            log?.invoke("jammed=$jammed agc=${agcDb?.let { "%.1f".format(Locale.US, it) }}")
            if (wasJammed && !jammed) {
                jamEndedAtMs = elapsedMs
                return true
            }
        }
        return false
    }

    /** Compass heading and vertical rotation rate from the orientation sensors. */
    fun onOrientation(headingDeg: Float?, yawRateDegS: Float?, elapsedMs: Long) {
        compassDeg = headingDeg
        yawRateDegS?.let { gyroBias.addYawRate(elapsedMs, it.toDouble()) }
    }

    /** Recompute [gpsState] for time [nowMs] (logs changes). */
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

    /** Everything the engine needs for one tick, as an immutable value. */
    fun snapshot(nowMs: Long): PositioningSnapshot {
        updateGpsState(nowMs)
        return PositioningSnapshot(lastUsable, lastGood, lastNet, lastCell, gpsState, jammed, compassDeg)
    }
}
