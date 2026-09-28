package org.blinddriver.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import org.blinddriver.app.power.PowerProfile
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
    private val context: Context,
    private val hub: PositioningHub,
    private val onImu: (ImuSample) -> Unit,
    private val log: (String) -> Unit,
    /** Called once per detected step (walking trips only), with elapsedRealtime ms. */
    private val onStep: (Long) -> Unit = {},
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val rotation = FloatArray(9)
    private var gyro: FloatArray? = null
    private var linearAcc: FloatArray? = null
    private val gravity = FloatArray(3)
    private var gravityInit = false

    /** Human-readable description of missing sensors, null if the phone has everything. */
    val sensorWarning: String? = run {
        val missing = listOf(
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_ROTATION_VECTOR to "rotation vector",
            Sensor.TYPE_MAGNETIC_FIELD to "compass",
        ).filter { sensorManager.getDefaultSensor(it.first) == null }.map { it.second }
        if (missing.isEmpty()) null else "No ${missing.joinToString()}: gyro turn detection off, stop detection from accelerometer only"
    }

    private val gpsListener = LocationListener { hub.onFix(it.toRawFix(FixSource.GPS)) }
    private val netListener = LocationListener { hub.onFix(it.toRawFix(FixSource.NET)) }

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

    /** Android's step detector: one event per step, fine in a hand, pocket or bag. */
    private val stepListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) = onStep(SystemClock.elapsedRealtime())
    }

    /** Does this phone have a step detector? (Most do; some budget phones do not.) */
    val hasStepDetector: Boolean get() = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

    private var stepsRegistered = false

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> gyro = e.values.clone()
                Sensor.TYPE_LINEAR_ACCELERATION -> linearAcc = e.values.clone()
                Sensor.TYPE_ROTATION_VECTOR -> onRotation(e.values)
                Sensor.TYPE_ACCELEROMETER -> onRawAccel(e.values)
            }
        }
    }

    /** Rotation-vector sensor event: derive the compass heading and the vertical turn rate, then emit an [ImuSample]. */
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

    /**
     * Fallback for phones without gyro / rotation vector: estimate gravity with a ~1 s low-pass
     * filter and subtract it. Enough for the stop/resume detector; no heading or yaw rate.
     */
    private fun onRawAccel(v: FloatArray) {
        if (!gravityInit) {
            v.copyInto(gravity)
            gravityInit = true
        }
        val linear = FloatArray(3)
        for (i in 0..2) {
            gravity[i] = 0.98f * gravity[i] + 0.02f * v[i]
            linear[i] = v[i] - gravity[i]
        }
        onImu(ImuSample(SystemClock.elapsedRealtime(), null, null, linear, null))
    }

    /** False when the user switched Location off system-wide: no provider will deliver fixes. */
    val locationEnabled: Boolean get() = locationManager.isLocationEnabled

    /** What is currently registered; [configure] only touches what changed. */
    private var profile: PowerProfile = PowerProfile.BALANCED
    private var navigating = false
    private var imuConfig: Pair<Int, Boolean>? = null
    private var netMinMs = -1L
    private var measurements = false
    private var lastSummary = ""

    /**
     * Apply a power profile. While [navigating] the IMU runs at the profile's rate (turn and stop
     * detection); otherwise only orientation at a low rate (compass plausibility check, gyro bias).
     */
    fun configure(p: PowerProfile, navigating: Boolean, walking: Boolean = false) {
        profile = p
        this.navigating = navigating
        this.walking = walking
        if (running) applyConfig()
    }

    /** Walking trip in progress: listen to the step detector. */
    private var walking = false

    /** Steps need the "physical activity" permission (Android 10+). */
    private fun canCountSteps(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun applyStepConfig() {
        val want = running && navigating && walking && hasStepDetector && canCountSteps()
        if (want == stepsRegistered) return
        if (want) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sensorManager.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
        } else {
            sensorManager.unregisterListener(stepListener)
        }
        stepsRegistered = want
        log("step_detector ${if (want) "on" else "off"}")
    }

    /** (Re-)register listeners so they match the current power profile; only what changed is touched. */
    @SuppressLint("MissingPermission")
    private fun applyConfig() {
        val p = profile
        if (netMinMs != p.networkMinMs) {
            locationManager.removeUpdates(netListener)
            runCatching { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, p.networkMinMs, 0f, netListener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "network: $it") }
            netMinMs = p.networkMinMs
        }
        // Never drop the jamming indicator while it reports jamming: the state would freeze.
        val wantMeasurements = p.gnssMeasurements || hub.jammed
        if (measurements != wantMeasurements) {
            if (wantMeasurements) {
                runCatching { locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback, handler) }
                    .onFailure { Log.w(TAG, "gnss measurements: $it") }
            } else {
                locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
            }
            measurements = wantMeasurements
        }
        val imu = (if (navigating) p.imuPeriodUs else IDLE_IMU_PERIOD_US) to navigating
        if (imuConfig != imu) {
            sensorManager.unregisterListener(sensorListener)
            val hasRotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
            val types = when {
                hasRotation && navigating -> listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION)
                hasRotation -> listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE)
                navigating -> listOf(Sensor.TYPE_ACCELEROMETER)
                else -> emptyList() // accelerometer-only phones: nothing useful without a route
            }
            if (!navigating) linearAcc = null
            for (type in types) {
                sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(sensorListener, it, imu.first, handler) }
            }
            imuConfig = imu
        }
        applyStepConfig()
        val summary = "power ${p.name} nav=$navigating imu_hz=${1_000_000 / imu.first} net_ms=${p.networkMinMs} agc=$measurements"
        if (summary != lastSummary) log(summary)
        lastSummary = summary
    }

    /** Start GPS, network location, satellite status and motion sensors. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        injectAssistance("start")
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) }
            .onFailure { Log.w(TAG, "gps: $it") }
        // The fused provider is not requested: it is built largely from GPS and inherits spoofed positions.
        runCatching { locationManager.registerGnssStatusCallback(gnssStatusCallback, handler) }
            .onFailure { Log.w(TAG, "gnss status: $it") }
        sensorWarning?.let { log("sensors: $it") }
        applyConfig()
    }

    /** Stop everything [start] registered. */
    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(netListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        if (measurements) locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
        sensorManager.unregisterListener(sensorListener)
        sensorManager.unregisterListener(stepListener)
        stepsRegistered = false
        netMinMs = -1L
        measurements = false
        imuConfig = null
    }

    /** Ask the GNSS chip to refresh assistance data (ephemeris, time) — speeds up recovery after jamming. */
    private fun injectAssistance(reason: String) {
        val xtra = runCatching { locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null) }.getOrNull()
        val time = runCatching { locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null) }.getOrNull()
        log("agps_inject reason=$reason xtra=$xtra time=$time")
    }

    /** Android [Location] → our platform-independent [RawFix]. */
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
        isMock = if (Build.VERSION.SDK_INT >= 31) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        },
    )

    private companion object {
        const val TAG = "SensorHub"

        /** 5 Hz: enough for heading and gyro-bias learning when no route is being followed. */
        const val IDLE_IMU_PERIOD_US = 200_000
    }
}
