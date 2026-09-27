package org.blinddriver.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.blinddriver.core.gnss.FixSource
import org.blinddriver.core.gnss.PositioningHub
import org.blinddriver.core.gnss.RawFix
import org.blinddriver.core.imu.ImuSample
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Bridges Android location + sensor APIs to the platform-independent core:
 *  - GPS / network / fused fixes → [PositioningHub.onFix]
 *  - GnssStatus (satellites, C/N0) and GnssMeasurements (AGC) → receiver health
 *  - rotation vector + gyro + linear acceleration → heading, vertical yaw rate, [ImuSample]
 *
 * All callbacks are delivered on the main looper.
 */
class SensorHub(
    context: Context,
    private val hub: PositioningHub,
    private val onImu: (ImuSample) -> Unit,
    private val log: (String) -> Unit,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val rotation = FloatArray(9)
    private var gyro: FloatArray? = null
    private var linearAcc: FloatArray? = null

    private val gpsListener = LocationListener { hub.onFix(it.toRawFix(FixSource.GPS)) }
    private val netListener = LocationListener { hub.onFix(it.toRawFix(FixSource.NET)) }
    private val fusedListener = LocationListener { hub.onFix(it.toRawFix(FixSource.FUSED)) }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var dualUsed = 0
            var cn0SumUsed = 0f
            var cn0SumAll = 0f
            var cn0CountAll = 0
            val cn0Used = ArrayList<Float>()
            for (i in 0 until status.satelliteCount) {
                val cn0 = status.getCn0DbHz(i)
                if (cn0 > 0f) {
                    cn0SumAll += cn0
                    cn0CountAll++
                }
                if (status.usedInFix(i)) {
                    used++
                    cn0SumUsed += cn0
                    cn0Used += cn0
                    if (status.hasCarrierFrequencyHz(i) && status.getCarrierFrequencyHz(i) < 1.5e9f) dualUsed++
                }
            }
            val mean = if (used > 0) cn0SumUsed / used else null
            val spread = if (used > 1 && mean != null) {
                sqrt(cn0Used.sumOf { ((it - mean) * (it - mean)).toDouble() } / used).toFloat()
            } else {
                null
            }
            hub.onGnssStatus(
                visible = status.satelliteCount,
                used = used,
                meanCn0Used = mean,
                cn0SpreadUsed = spread,
                meanCn0Visible = if (cn0CountAll > 0) cn0SumAll / cn0CountAll else null,
                dualFrequencyUsed = dualUsed,
                elapsedMs = SystemClock.elapsedRealtime(),
            )
        }
    }

    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        @Suppress("DEPRECATION")
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val levels: List<Double> = if (Build.VERSION.SDK_INT >= 33 && event.gnssAutomaticGainControls.isNotEmpty()) {
                event.gnssAutomaticGainControls.map { it.levelDb }
            } else {
                event.measurements.filter { it.hasAutomaticGainControlLevelDb() }.map { it.automaticGainControlLevelDb }
            }
            val agc = if (levels.isEmpty()) null else levels.average().toFloat()
            if (hub.onAgc(agc, SystemClock.elapsedRealtime())) injectAssistance("jam_end")
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> gyro = e.values.clone()
                Sensor.TYPE_LINEAR_ACCELERATION -> linearAcc = e.values.clone()
                Sensor.TYPE_ROTATION_VECTOR -> onRotation(e.values)
            }
        }
    }

    private fun onRotation(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotation, values)
        // Heading of the device's "forward" axis, choosing the axis that is most horizontal so
        // it works both flat on a seat and upright in a windscreen mount.
        var x = rotation[1]
        var y = rotation[4]
        val ux = -rotation[2]
        val uy = -rotation[5]
        if (ux * ux + uy * uy > x * x + y * y) {
            x = ux
            y = uy
        }
        val heading = ((Math.toDegrees(atan2(x.toDouble(), y.toDouble())) + 360.0) % 360.0).toFloat()
        // Angular velocity about world-up (row 3 of R is world Z in device coordinates),
        // negated so clockwise = positive like compass bearings.
        val g = gyro
        val yawRate = g?.let { -Math.toDegrees((it[0] * rotation[6] + it[1] * rotation[7] + it[2] * rotation[8]).toDouble()).toFloat() }
        val now = SystemClock.elapsedRealtime()
        hub.onOrientation(heading, yawRate, now)
        onImu(ImuSample(now, heading, yawRate, linearAcc, g))
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        injectAssistance("start")
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) }
            .onFailure { Log.w(TAG, "gps: $it") }
        runCatching { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, netListener, Looper.getMainLooper()) }
            .onFailure { Log.w(TAG, "network: $it") }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0L, 0f, fusedListener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "fused: $it") }
        }
        runCatching { locationManager.registerGnssStatusCallback(gnssStatusCallback, handler) }
            .onFailure { Log.w(TAG, "gnss status: $it") }
        runCatching { locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback, handler) }
            .onFailure { Log.w(TAG, "gnss measurements: $it") }
        for (type in listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION)) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(netListener)
        locationManager.removeUpdates(fusedListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
        sensorManager.unregisterListener(sensorListener)
    }

    /** Ask the GNSS chip to refresh assistance data (ephemeris, time) — speeds up recovery after jamming. */
    private fun injectAssistance(reason: String) {
        val xtra = runCatching { locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null) }.getOrNull()
        val time = runCatching { locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null) }.getOrNull()
        log("agps_inject reason=$reason xtra=$xtra time=$time")
    }

    private fun Location.toRawFix(source: FixSource) = RawFix(
        source = source,
        timeMs = time,
        elapsedMs = elapsedRealtimeNanos / 1_000_000,
        lat = latitude,
        lon = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        isMock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider,
    )

    private companion object {
        const val TAG = "SensorHub"
    }
}
