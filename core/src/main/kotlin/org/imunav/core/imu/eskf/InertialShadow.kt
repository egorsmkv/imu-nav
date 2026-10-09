package org.imunav.core.imu.eskf

import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.JudgedFix
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustLevel
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.sin

/** Raw Android sensor kind. ATTITUDE is scalar-first device-to-TRUE-ENU, used only for initialization. */
enum class InertialKind { ACCELEROMETER, GYROSCOPE, ATTITUDE }

/**
 * One physical sensor event, not the cached vectors in legacy ImuSample. timestampNs is the sensor's
 * elapsed-realtime timestamp; vector is m/s² INCLUDING gravity, rad/s, or quaternion xyz respectively.
 * scalar is quaternion w for ATTITUDE. Values and nanoseconds are recorded losslessly.
 */
data class InertialSample(val timestampNs: Long, val kind: InertialKind, val vector: Vector3, val scalar: Double = 0.0)

/** Diagnostic estimate only; never supplied to the navigation engine or interpreted as a safety bound. */
data class InertialEstimate(val state: InertialState, val point: GeoPoint, val horizontalSigmaM: Double)

/**
 * Mounted-car shadow experiment. Reorders IMU and GOOD GPS by measurement time in a bounded 250 ms
 * window; measurements arriving later are rejected, never applied at the wrong epoch. Initialization
 * requires a fresh attitude, accelerometer, gyro and GOOD GPS speed. No route, network fix, OBD,
 * inferred stationarity or Android linear-acceleration signal is fed back into this filter.
 * Call on one thread. Feed recorded events in ARRIVAL order, not sorted by their measurement times.
 */
class InertialShadow(private val onEstimate: (InertialEstimate) -> Unit = {}) {
    private data class Input(val timestampNs: Long, val sequence: Long, val sample: InertialSample? = null, val fix: RawFix? = null)
    private val pending = PriorityQueue(compareBy<Input> { it.timestampNs }.thenBy { it.sequence })
    private var sequence = 0L
    private var newestSensorNs = -1L
    private var processedNs = -1L
    private val sensorTimes = LongArray(InertialKind.entries.size) { -1L }
    private var lastGpsNs = -1L
    private var acceleration: InertialSample? = null
    private var gyroscope: InertialSample? = null
    private var attitude: InertialSample? = null
    private var filter: ErrorStateEkf? = null
    private var projection: LocalProjection? = null
    private var originAltitude: Double? = null
    var rejectedInputs = 0
        private set
    var acceptedGps = 0
        private set
    var resets = 0
        private set

    /** Sensor events drive the watermark; no future IMU sample is borrowed to fill missing data. */
    fun onSensor(sample: InertialSample) {
        val previous = sensorTimes[sample.kind.ordinal]
        if (sample.timestampNs < 0 || sample.timestampNs <= previous || !valid(sample)) {
            rejectedInputs++
            return
        }
        sensorTimes[sample.kind.ordinal] = sample.timestampNs
        newestSensorNs = maxOf(newestSensorNs, sample.timestampNs)
        enqueue(Input(sample.timestampNs, sequence++, sample = sample))
        val watermark = newestSensorNs - REORDER_NS
        while (pending.isNotEmpty() && pending.peek().timestampNs <= watermark) {
            val input = pending.remove()
            processedNs = input.timestampNs
            input.sample?.let { applySensor(it) }
            input.fix?.let { applyGps(it) }
            estimate(input.timestampNs)?.let { onEstimate(it) }
        }
    }

    /** Never accepts fused/network/mock/SUSPECT/BAD fixes, including during initialization. */
    fun onGps(judged: JudgedFix) {
        val fix = judged.fix
        if (judged.verdict.level != TrustLevel.GOOD || fix.source != FixSource.GPS || fix.isMock || !validGps(fix)) return
        val timestampNs = fix.elapsedMs * NS_PER_MS
        if (timestampNs <= lastGpsNs) {
            rejectedInputs++
            return
        }
        lastGpsNs = timestampNs
        enqueue(Input(timestampNs, sequence++, fix = fix))
    }

    /** Stale estimates disappear rather than continuing to look usable after sensors stop. */
    fun estimate(nowNs: Long): InertialEstimate? {
        val current = filter ?: return null
        val local = projection ?: return null
        if (nowNs - current.state.timestampNs !in 0..MAX_ESTIMATE_AGE_NS) return null
        return InertialEstimate(current.state, local.toGeo(current.state.position.x, current.state.position.y), current.horizontalSigmaM)
    }

    private fun enqueue(input: Input) {
        if (input.timestampNs < processedNs || pending.size >= MAX_PENDING) {
            rejectedInputs++
        } else {
            pending.add(input)
        }
    }

    private fun applySensor(sample: InertialSample) {
        when (sample.kind) {
            InertialKind.ATTITUDE -> attitude = sample

            InertialKind.ACCELEROMETER -> acceleration = sample

            InertialKind.GYROSCOPE -> {
                gyroscope = sample
                val current = filter
                if (current != null && sample.timestampNs - current.state.timestampNs >= PREDICT_PERIOD_NS) advance(sample.timestampNs)
            }
        }
    }

    private fun advance(timestampNs: Long): Boolean {
        val current = filter ?: return false
        if (timestampNs == current.state.timestampNs) return true
        val acc = acceleration
        val gyro = gyroscope
        val fresh = acc != null && gyro != null && timestampNs - acc.timestampNs in 0..MAX_SENSOR_AGE_NS && timestampNs - gyro.timestampNs in 0..MAX_SENSOR_AGE_NS
        if (!fresh || !current.predict(timestampNs, acc.vector, gyro.vector)) {
            filter = null
            projection = null
            resets++
            return false
        }
        return true
    }

    private fun applyGps(fix: RawFix) {
        if (filter == null) {
            initialize(fix)
            return
        }
        if (!advance(fix.elapsedMs * NS_PER_MS)) return
        val current = requireNotNull(filter)
        val local = requireNotNull(projection)
        val point = GeoPoint(fix.lat, fix.lon)
        val coordinates = mutableListOf(0, 1)
        val observations = mutableListOf(local.x(point), local.y(point))
        val sigma = maxOf(MIN_GPS_SIGMA_M, requireNotNull(fix.accuracyM).toDouble())
        val variances = mutableListOf(sigma * sigma, sigma * sigma)
        val altitude = fix.altitudeM
        val verticalSigma = fix.verticalAccuracyM
        originAltitude?.let { origin ->
            if (altitude != null && altitude.isFinite() && verticalSigma != null && verticalSigma.isFinite() && verticalSigma > 0f) {
                coordinates += 2
                observations += altitude - origin
                variances += maxOf(MIN_VERTICAL_SIGMA_M, verticalSigma.toDouble()).let { it * it }
            }
        }
        // Course accuracy is not recorded by legacy GPS inputs. A conservative 2 m/s floor avoids
        // treating speed accuracy alone as accurate two-dimensional velocity, especially in turns.
        gpsVelocity(fix)?.let { velocity ->
            val speedSigma = maxOf(MIN_SPEED_SIGMA_MPS, fix.speedAccuracyMps?.takeIf { it.isFinite() && it > 0 }?.toDouble() ?: MIN_SPEED_SIGMA_MPS)
            coordinates += listOf(3, 4)
            observations += listOf(velocity.x, velocity.y)
            variances += listOf(speedSigma * speedSigma, speedSigma * speedSigma)
        }
        if (current.correct(coordinates.toIntArray(), observations.toDoubleArray(), variances.toDoubleArray())) acceptedGps++ else rejectedInputs++
    }

    private fun initialize(fix: RawFix) {
        val timestampNs = fix.elapsedMs * NS_PER_MS
        val orientation = attitude ?: return
        val acc = acceleration ?: return
        val gyro = gyroscope ?: return
        if (timestampNs - orientation.timestampNs !in 0..MAX_ATTITUDE_AGE_NS ||
            timestampNs - acc.timestampNs !in 0..MAX_SENSOR_AGE_NS || timestampNs - gyro.timestampNs !in 0..MAX_SENSOR_AGE_NS
        ) {
            return
        }
        val velocity = gpsVelocity(fix) ?: return
        projection = LocalProjection(GeoPoint(fix.lat, fix.lon))
        originAltitude = fix.altitudeM?.takeIf { it.isFinite() }
        val quaternion = Attitude(orientation.scalar, orientation.vector.x, orientation.vector.y, orientation.vector.z).normalized()
        filter = ErrorStateEkf(InertialState(timestampNs, Vector3.ZERO, velocity, quaternion), maxOf(MIN_GPS_SIGMA_M, requireNotNull(fix.accuracyM).toDouble()))
        acceptedGps++
    }

    private fun gpsVelocity(fix: RawFix): Vector3? {
        val speed = fix.speedMps?.toDouble()?.takeIf { it.isFinite() && it in 0.0..MAX_SPEED_MPS } ?: return null
        val bearing = fix.bearingDeg?.toDouble()?.takeIf { it.isFinite() && it in 0.0..360.0 }
        if (bearing == null && speed >= STATIONARY_SPEED_MPS) return null
        val radians = Math.toRadians(bearing ?: 0.0)
        return Vector3(speed * sin(radians), speed * cos(radians), 0.0)
    }

    private fun validGps(fix: RawFix) = fix.elapsedMs in 0..Long.MAX_VALUE / NS_PER_MS && fix.lat.isFinite() && fix.lat in -85.0..85.0 &&
        fix.lon.isFinite() && fix.lon in -180.0..180.0 && fix.accuracyM?.let { it.isFinite() && it > 0f } == true

    private fun valid(sample: InertialSample): Boolean {
        return !(!sample.vector.isFinite() || !sample.scalar.isFinite()) && when (sample.kind) {
            InertialKind.ACCELEROMETER -> sample.vector.norm() <= 200.0
            InertialKind.GYROSCOPE -> sample.vector.norm() <= 35.0
            InertialKind.ATTITUDE -> (sample.vector.norm().let { it * it } + sample.scalar * sample.scalar) in 0.9..1.1
        }
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
        private const val REORDER_NS = 250_000_000L
        private const val PREDICT_PERIOD_NS = 20_000_000L
        private const val MAX_SENSOR_AGE_NS = 150_000_000L
        private const val MAX_ATTITUDE_AGE_NS = 1_000_000_000L
        private const val MAX_ESTIMATE_AGE_NS = 600_000_000L
        private const val MAX_PENDING = 512
        private const val MIN_GPS_SIGMA_M = 3.0
        private const val MIN_VERTICAL_SIGMA_M = 5.0
        private const val MIN_SPEED_SIGMA_MPS = 2.0
        private const val MAX_SPEED_MPS = 100.0
        private const val STATIONARY_SPEED_MPS = 0.5
    }
}
