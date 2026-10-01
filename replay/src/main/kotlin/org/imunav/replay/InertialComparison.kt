package org.imunav.replay

import org.imunav.core.Tuning
import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.imu.eskf.InertialEstimate
import org.imunav.core.imu.eskf.InertialShadow
import org.imunav.core.record.ReplayStats
import org.imunav.core.record.TripEvent
import org.imunav.core.route.TravelMode
import java.util.TreeMap
import java.util.TreeSet

/** Identical reference timestamp and horizontal (not along-route) metric for all three estimators. */
data class InertialComparisonSample(
    val elapsedMs: Long,
    val blind: Boolean,
    val kotlinErrorM: Double,
    val nativeErrorM: Double,
    val inertialErrorM: Double,
    val inertialSigmaM: Double,
    val inertialPoint: GeoPoint,
)

/** Missing estimates remain explicit: a filter that never initializes must not look perfectly accurate. */
data class InertialComparisonResult(val samples: List<InertialComparisonSample>, val unpaired: Int, val rejected: Int, val resets: Int) {
    fun summary(): String = buildString {
        appendLine("experimental ESKF: paired horizontal errors; GPS reference is not survey ground truth")
        for (blind in listOf(false, true)) {
            val paired = samples.filter { it.blind == blind }
            val label = if (blind) "GPS hidden" else "GPS available"
            appendLine("$label, Kotlin: ${ReplayStats.of(paired.map { it.kotlinErrorM })}")
            appendLine("$label, native: ${ReplayStats.of(paired.map { it.nativeErrorM })}")
            appendLine("$label, ESKF:   ${ReplayStats.of(paired.map { it.inertialErrorM })}")
        }
        appendLine("unpaired reference samples=$unpaired rejected inputs=$rejected resets=$resets")
        appendLine("ESKF sigma is diagnostic, not a safety radius; no accuracy improvement is assumed")
    }

    fun csv(): String = buildString {
        appendLine("elapsed_ms,blind,kotlin_error_m,native_error_m,eskf_error_m,eskf_sigma_m,eskf_lat,eskf_lon")
        for (sample in samples) {
            appendLine(
                listOf(
                    sample.elapsedMs,
                    sample.blind,
                    sample.kotlinErrorM,
                    sample.nativeErrorM,
                    sample.inertialErrorM,
                    sample.inertialSigmaM,
                    sample.inertialPoint.lat,
                    sample.inertialPoint.lon,
                ).joinToString(","),
            )
        }
    }
}

/**
 * Three-way experiment using new raw `U` inputs. GPS hiding also excludes initialization and bias
 * learning. The inertial branch preserves recording arrival order and the live reordering window.
 * Kotlin/native baselines use NativeComparison; this is an A/B evaluation, not exact app restoration.
 */
class InertialComparison(private val tuning: Tuning = Tuning.DEFAULT, private val area: ServiceArea = ServiceArea.EVERYWHERE) {
    fun replay(events: List<TripEvent>, hideGpsAfterS: Double? = null): InertialComparisonResult {
        require(
            events.any {
                it is TripEvent.Inertial
            },
        ) { "No raw inertial inputs (U). Record a mounted-car trip with Inertial ESKF shadow enabled; legacy I samples are insufficient." }
        require(events.filterIsInstance<TripEvent.Mode>().none { it.mode == TravelMode.FOOT }) { "Inertial ESKF is currently a mounted-car experiment, not a walking estimator." }
        require(hideGpsAfterS == null || (hideGpsAfterS.isFinite() && hideGpsAfterS >= 0.0))
        val session = Session(hideGpsAfterS)
        events.forEach(session::apply)
        val baselines = NativeComparison(tuning, area).replay(events, hideGpsAfterS)
        val paired = baselines.samples.mapNotNull { baseline ->
            val estimate = session.at(baseline.elapsedMs) ?: return@mapNotNull null
            InertialComparisonSample(
                baseline.elapsedMs,
                baseline.blind,
                Geo.distance(baseline.kotlinPoint, baseline.truthPoint),
                Geo.distance(baseline.nativePoint, baseline.truthPoint),
                Geo.distance(estimate.first, baseline.truthPoint),
                estimate.second,
                estimate.first,
            )
        }
        return InertialComparisonResult(paired, baselines.samples.size - paired.size, session.rejected, session.resets)
    }

    private inner class Session(private val hideGpsAfterS: Double?) {
        private var clockOffset: Long? = null
        private var nowMs = 0L
        private var startMs: Long? = null
        private var hasTrip = false
        private val starts = TreeSet<Long>()
        private val hub = PositioningHub(area = area, wallClock = { nowMs + (clockOffset ?: 0L) })
        private val estimates = TreeMap<Long, InertialEstimate>()
        private var shadow = newShadow()
        private var previousRejected = 0
        private var previousResets = 0
        val rejected get() = previousRejected + shadow.rejectedInputs
        val resets get() = previousResets + shadow.resets

        init {
            hub.judgedFixObserver = { if (startMs != null) shadow.onGps(it) }
        }

        private fun newShadow() = InertialShadow { estimates[it.state.timestampNs] = it }
        private fun blind(timeMs: Long) = hideGpsAfterS?.let { after -> startMs?.let { timeMs - it >= after * 1000 } } == true

        /** Match live event order, including late GPS and per-sensor delivery skew. */
        fun apply(event: TripEvent) {
            nowMs = maxOf(nowMs, event.elapsedMs)
            when (event) {
                is TripEvent.Start -> {
                    previousRejected += shadow.rejectedInputs
                    previousResets += shadow.resets
                    shadow = newShadow()
                    startMs = null
                    hasTrip = true
                    starts.add(event.elapsedMs)
                }

                is TripEvent.RouteSet -> if (hasTrip && startMs == null) startMs = event.elapsedMs

                is TripEvent.Stop -> {
                    startMs = null
                    hasTrip = false
                }

                is TripEvent.Fix -> {
                    val fix = event.fix
                    if (clockOffset == null && fix.source == FixSource.GPS) clockOffset = fix.timeMs - fix.elapsedMs
                    if (fix.source != FixSource.GPS || !blind(fix.elapsedMs)) hub.onFix(fix)
                }

                is TripEvent.Inertial -> if (startMs != null) shadow.onSensor(event.sample)

                is TripEvent.Imu -> {
                    hub.onOrientation(event.sample.headingDeg, event.sample.yawRateDegS, event.elapsedMs)
                }

                is TripEvent.Gnss -> {
                    hub.onGnssStatus(event.visible, event.used, event.meanCn0Used, event.cn0SpreadUsed, event.meanCn0Visible, event.dualFrequencyUsed, event.elapsedMs)
                }

                is TripEvent.Agc -> {
                    hub.onAgc(event.agcDb, event.elapsedMs)
                }

                else -> Unit // recorded estimates and routes never initialize or constrain ESKF
            }
        }

        /** Use only a preceding estimate, aligned by ≤100 ms (saver sensor period) of its own velocity. */
        fun at(timeMs: Long): Pair<GeoPoint, Double>? {
            val timeNs = timeMs * 1_000_000L
            val preceding = estimates.floorEntry(timeNs)?.value ?: return null
            val segmentStart = starts.floor(timeMs) ?: return null
            if (preceding.state.timestampNs < segmentStart * 1_000_000L) return null
            val deltaNs = timeNs - preceding.state.timestampNs
            if (deltaNs !in 0..MAX_ALIGNMENT_NS) return null
            val seconds = deltaNs / 1e9
            val point = LocalProjection(preceding.point).toGeo(preceding.state.velocity.x * seconds, preceding.state.velocity.y * seconds)
            return point to preceding.horizontalSigmaM
        }
    }

    private companion object {
        const val MAX_ALIGNMENT_NS = 100_000_000L
    }
}
