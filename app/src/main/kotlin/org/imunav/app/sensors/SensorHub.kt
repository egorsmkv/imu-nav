package org.imunav.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.imunav.app.power.PowerProfile
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.gnss.RawFix
import org.imunav.core.imu.GravityFilter
import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.TimedSensorVector
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Bridges Android location + sensor APIs to the platform-independent core:
 *  - GPS / network / fused fixes → [PositioningHub.onFix]
 *  - GnssStatus (satellites, C/N0) and GnssMeasurements (AGC) → receiver health
 *  - rotation vector + gyro + linear acceleration → heading, vertical yaw rate, [ImuSample]
 *  - barometer (while navigating) → air pressure for terrain matching
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
    /** Air pressure in hPa with elapsedRealtime ms (navigating, phones with a barometer). */
    private val onPressure: (Float, Long) -> Unit = { _, _ -> },
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Registering and unregistering listeners are calls into system services that can take
     *  hundreds of ms; they run on this thread, one at a time. Callbacks still arrive on the main thread. */
    private val control = Executors.newSingleThreadExecutor { Thread(it, "sensor-control") }

    // Lifecycle flag is control-worker confined; measurement caches below belong to the main looper.
    private var running = false

    private val rotation = FloatArray(9)
    private val gyro = TimedSensorVector()
    private val linearAcc = TimedSensorVector()
    private val gravityFilter = GravityFilter()
    private var lastRotationNs = -1L
    private var lastStepNs = -1L
    private var lastPressureNs = -1L
    private val inertialCapture = InertialCapture(hub)

    @Volatile private var rawAccelFallback = true

    /** Hardware availability is language-independent; the UI supplies translated sensor names. */
    val missingSensors: List<NavigationSensor> = NavigationSensor.entries.filter { sensorManager.getDefaultSensor(it.androidType) == null }

    /** Preserve the existing English diagnostic message for recordings and log analysis. */
    private val sensorWarning: String? = missingSensors.takeIf { it.isNotEmpty() }?.let { missing ->
        "No ${missing.joinToString { it.logName }}: gyro turn detection off, stop detection from accelerometer only"
    }

    private val gpsListener = FixLocationListener { hub.onFix(it.toRawFix(FixSource.GPS)) }
    private val netListener = FixLocationListener { hub.onFix(it.toRawFix(FixSource.NET)) }

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

        override fun onSensorChanged(e: SensorEvent) {
            if (e.timestamp < 0 || e.timestamp <= lastStepNs) return
            lastStepNs = e.timestamp
            onStep(e.timestamp / NS_PER_MS)
        }
    }

    /** The barometer: pressure changes give height changes to compare with the route's hills. */
    private val pressureListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) {
            if (e.timestamp < 0 || e.timestamp <= lastPressureNs || !e.values[0].isFinite()) return
            lastPressureNs = e.timestamp
            onPressure(e.values[0], e.timestamp / NS_PER_MS)
        }
    }

    /** Does this phone have a barometer? (Mid-range and better phones usually do.) */
    val hasBarometer: Boolean get() = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    /** Does this phone have a step detector? (Most do; some budget phones do not.) */
    val hasStepDetector: Boolean get() = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) {
            inertialCapture.onSensor(e)
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> gyro.update(e.values, e.timestamp)
                Sensor.TYPE_LINEAR_ACCELERATION -> linearAcc.update(e.values, e.timestamp)
                Sensor.TYPE_ROTATION_VECTOR -> onRotation(e.values, e.timestamp)
                Sensor.TYPE_ACCELEROMETER -> if (rawAccelFallback) onRawAccel(e.values, e.timestamp)
            }
        }
    }

    /** Rotation-vector sensor event: derive the compass heading and the vertical turn rate, then emit an [ImuSample]. */
    private fun onRotation(values: FloatArray, timestampNs: Long) {
        if (timestampNs < 0 || timestampNs <= lastRotationNs || values.size < 3 || values.any { !it.isFinite() }) return
        lastRotationNs = timestampNs
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
        val g = gyro.at(timestampNs)
        val yawRate = g?.let { -Math.toDegrees((it[0] * rotation[6] + it[1] * rotation[7] + it[2] * rotation[8]).toDouble()).toFloat() }
        val now = timestampNs / NS_PER_MS
        hub.onOrientation(heading, yawRate, now)
        onImu(ImuSample(now, heading, yawRate, linearAcc.at(timestampNs), g))
    }

    /**
     * Fallback for phones without gyro / rotation vector: estimate gravity with a ~1 s low-pass
     * filter and subtract it. Enough for the stop/resume detector; no heading or yaw rate.
     */
    private fun onRawAccel(values: FloatArray, timestampNs: Long) {
        val linear = gravityFilter.linearAcceleration(values, timestampNs) ?: return
        onImu(ImuSample(timestampNs / NS_PER_MS, null, null, linear, null))
    }

    /** Reset callback-owned state on its looper whenever registrations change. */
    private fun resetSamples() = handler.post {
        gyro.reset()
        linearAcc.reset()
        gravityFilter.reset()
        lastRotationNs = -1L
        lastStepNs = -1L
        lastPressureNs = -1L
    }

    private fun listenerFor(type: Int): SensorEventListener = when (type) {
        Sensor.TYPE_STEP_DETECTOR -> stepListener
        Sensor.TYPE_PRESSURE -> pressureListener
        else -> sensorListener
    }

    private val registrations = SensorRegistrations<Int>(
        register = { type, period ->
            runCatching {
                sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(listenerFor(type), it, period, handler) } == true
            }.onFailure { Log.w(TAG, "sensor registration type=$type: $it") }.getOrDefault(false)
        },
        unregister = { type ->
            runCatching { sensorManager.getDefaultSensor(type)?.let { sensorManager.unregisterListener(listenerFor(type), it) } }
                .onFailure { Log.w(TAG, "sensor cleanup type=$type: $it") }
        },
        failed = { type -> log("sensor_registration_failed type=$type") },
    )

    private val providerStatus = ProviderStatus(control) {
        if (Build.VERSION.SDK_INT >= 28) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /** Cached only: safe to include in every main-thread UI snapshot. */
    val locationEnabled: Boolean get() = providerStatus.enabled.value

    /** Fresh binder query on the sensor-control worker before starting a trip. */
    suspend fun checkLocationEnabled(): Boolean = providerStatus.check()

    init {
        // SensorHub has application lifetime, as does this receiver; no Activity is retained.
        control.execute {
            ContextCompat.registerReceiver(
                context.applicationContext,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) = providerStatus.refresh()
                },
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            providerStatus.refresh()
        }
    }

    /** What is currently registered; [configure] only touches what changed. */
    private var profile: PowerProfile = PowerProfile.BALANCED
    private var navigating = false
    private var inertialEnabled = false
    private var netMinMs = -1L
    private var measurements = false
    private var gpsRegistered = false
    private var statusRegistered = false
    private var lastSummary = ""

    /**
     * Apply a power profile. While [navigating] the IMU runs at the profile's rate (turn and stop
     * detection); otherwise only orientation at a low rate (compass plausibility check, gyro bias).
     */
    fun configure(p: PowerProfile, navigating: Boolean, walking: Boolean = false, inertial: Boolean = false) {
        inertialCapture.enabled = inertial && navigating && !walking
        val jammed = hub.jammed // snapshot core state on main; the control worker must not read it
        control.execute {
            this.jammed = jammed
            profile = p
            this.navigating = navigating
            this.walking = walking
            inertialEnabled = inertial && navigating && !walking
            if (running) applyConfig()
        }
    }

    /** Walking trip in progress: listen to the step detector. */
    private var walking = false
    private var jammed = false

    /** Steps need the "physical activity" permission on Android 10 and newer. */
    private fun canCountSteps(): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** (Re-)register listeners so they match the current power profile; only what changed is touched. */
    @SuppressLint("MissingPermission")
    private fun applyConfig() {
        registerPrimaryLocationListeners()
        val p = profile
        if (netMinMs != p.networkMinMs) {
            runCatching { locationManager.removeUpdates(netListener) }.onFailure { Log.w(TAG, "network cleanup: $it") }
            netMinMs = if (runCatching {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, p.networkMinMs, 0f, netListener, Looper.getMainLooper())
                }.onFailure { Log.w(TAG, "network: $it") }.isSuccess
            ) {
                p.networkMinMs
            } else {
                -1L
            }
        }
        // Never drop the jamming indicator while it reports jamming: the state would freeze.
        val wantMeasurements = p.gnssMeasurements || jammed
        if (measurements != wantMeasurements) {
            measurements = if (wantMeasurements) {
                runCatching { locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback, handler) }
                    .onFailure { Log.w(TAG, "gnss measurements: $it") }.getOrDefault(false)
            } else {
                runCatching { locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback) }
                    .onFailure { Log.w(TAG, "gnss measurements cleanup: $it") }
                false
            }
        }
        val period = if (navigating) p.imuPeriodUs else IDLE_IMU_PERIOD_US
        val wanted = wantedSensors(period)
        val hadPressure = registrations.contains(Sensor.TYPE_PRESSURE)
        val hadSteps = registrations.contains(Sensor.TYPE_STEP_DETECTOR)
        var changed = registrations.reconcile(wanted)
        rawAccelFallback = !registrations.contains(Sensor.TYPE_ROTATION_VECTOR)
        if (rawAccelFallback && navigating && sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            changed = registrations.ensure(Sensor.TYPE_ACCELEROMETER, period) || changed
        }
        if (changed) resetSamples()
        if (hadPressure != registrations.contains(Sensor.TYPE_PRESSURE)) log("barometer ${if (hadPressure) "off" else "on"}")
        if (hadSteps != registrations.contains(Sensor.TYPE_STEP_DETECTOR)) log("step_detector ${if (hadSteps) "off" else "on"}")
        val summary = "power ${p.name} nav=$navigating imu_hz=${1_000_000 / period} net_ms=${p.networkMinMs} agc=$measurements"
        if (summary != lastSummary) log(summary)
        lastSummary = summary
    }

    /** Only request sensors present on this device; a failed registration remains retryable. */
    private fun wantedSensors(period: Int): Map<Int, Int> = buildMap {
        val hasRotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        if (hasRotation) {
            put(Sensor.TYPE_ROTATION_VECTOR, period)
            put(Sensor.TYPE_GYROSCOPE, period)
            if (navigating) put(Sensor.TYPE_LINEAR_ACCELERATION, period)
        } else if (navigating) {
            put(Sensor.TYPE_ACCELEROMETER, period)
        }
        if (navigating && rawAccelFallback) put(Sensor.TYPE_ACCELEROMETER, period)
        if (inertialEnabled) {
            put(Sensor.TYPE_ACCELEROMETER, period)
            put(Sensor.TYPE_GYROSCOPE, period)
        }
        if (navigating && walking && canCountSteps()) put(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL)
        if (navigating) put(Sensor.TYPE_PRESSURE, PRESSURE_PERIOD_US)
    }.filterKeys { sensorManager.getDefaultSensor(it) != null }

    /** Start or retry only listeners whose previous registration failed. */
    @SuppressLint("MissingPermission")
    fun start() = control.execute {
        providerStatus.refresh()
        if (!running) {
            running = true
            injectAssistance("start")
            sensorWarning?.let { log("sensors: $it") }
        }
        applyConfig()
    }

    /** Retry primary location listeners on both start and profile/permission configuration changes. */
    @SuppressLint("MissingPermission")
    private fun registerPrimaryLocationListeners() {
        if (!gpsRegistered) {
            gpsRegistered = runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "gps: $it") }.isSuccess
        }
        // Never request the fused provider: it inherits spoofed GPS positions.
        if (!statusRegistered) {
            statusRegistered = runCatching { locationManager.registerGnssStatusCallback(gnssStatusCallback, handler) }
                .onFailure { Log.w(TAG, "gnss status: $it") }.getOrDefault(false)
        }
    }

    /** Stop everything [start] registered. */
    fun stop() = control.execute {
        if (!running) return@execute
        running = false
        listOf<() -> Unit>(
            { locationManager.removeUpdates(gpsListener) },
            { locationManager.removeUpdates(netListener) },
            { locationManager.unregisterGnssStatusCallback(gnssStatusCallback) },
            { locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback) },
        ).forEach { cleanup -> runCatching(cleanup).onFailure { Log.w(TAG, "location cleanup: $it") } }
        registrations.reconcile(emptyMap())
        resetSamples()
        gpsRegistered = false
        statusRegistered = false
        netMinMs = -1L
        measurements = false
    }

    /** Ask the GNSS chip to refresh assistance data (ephemeris, time) — speeds up recovery after jamming. */
    private fun injectAssistance(reason: String) = control.execute {
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
        const val NS_PER_MS = 1_000_000L

        /** 5 Hz: enough for heading and gyro-bias learning when no route is being followed. */
        const val IDLE_IMU_PERIOD_US = 200_000

        /** 5 Hz barometer: heights are smoothed over ~2 s anyway. */
        const val PRESSURE_PERIOD_US = 200_000
    }
}
