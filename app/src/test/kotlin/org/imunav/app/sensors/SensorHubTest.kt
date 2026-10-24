package org.imunav.app.sensors

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.imunav.app.power.PowerProfile
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.imu.ImuSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.util.concurrent.TimeUnit

/** Android sensors and location providers feed the positioning hub and the engine's IMU input. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorHubTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val positioning = PositioningHub()
    private val imu = ArrayList<ImuSample>()
    private val steps = ArrayList<Long>()
    private val pressure = ArrayList<Float>()
    private val log = ArrayList<String>()
    private lateinit var sensors: ShadowSensorManager
    private lateinit var hub: SensorHub
    private val installed = HashMap<Int, Sensor>()

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${log.takeLast(5)}" }
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun send(type: Int, vararg values: Float) {
        val event = SensorEventBuilder.newBuilder().setSensor(installed.getValue(type)).setValues(values).setTimestamp(SystemClock.elapsedRealtimeNanos()).build()
        sensors.sendSensorEventToListeners(event, installed.getValue(type))
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
    }

    @Before
    fun setUp() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        sensors = shadowOf(context.getSystemService(SensorManager::class.java))
        for (type in listOf(
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_STEP_DETECTOR,
        )) {
            installed[type] = ShadowSensor.newInstance(type).also(sensors::addSensor)
        }
        hub = SensorHub(context, positioning, { imu += it }, { log += it }, { steps += it }, { hPa, _ -> pressure += hPa })
    }

    @After
    fun tearDown() {
        hub.stop()
    }

    @Test
    fun navigationSensorsDeliverImuStepsPressureAndFixes() {
        assertTrue(hub.hasBarometer)
        assertTrue(hub.hasStepDetector)
        hub.start()
        hub.configure(PowerProfile.PERFORMANCE, navigating = true, walking = true, inertial = true)
        waitFor("sensor registration") { "barometer on" in log && log.any { it.startsWith("power performance") } }

        send(Sensor.TYPE_GYROSCOPE, 0f, 0f, 0.2f)
        send(Sensor.TYPE_LINEAR_ACCELERATION, 0.1f, 0.5f, 0f)
        send(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0.38f, 0.92f)
        send(Sensor.TYPE_STEP_DETECTOR, 1f)
        send(Sensor.TYPE_PRESSURE, 1013.2f)
        waitFor("IMU sample") { imu.isNotEmpty() }
        assertNotNull("the rotation vector gives a compass heading", imu.last().headingDeg)
        assertNotNull(positioning.compassDeg)
        assertEquals(1, steps.size)
        assertEquals(listOf(1013.2f), pressure)

        val locations = shadowOf(context.getSystemService(LocationManager::class.java))
        locations.simulateGnssStatus(
            GnssStatus.Builder().addSatellite(GnssStatus.CONSTELLATION_GPS, 12, 38f, 45f, 120f, true, true, true, true, 1575.42e6f, false, 0f).build(),
        )
        val gps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 50.45
            longitude = 30.52
            accuracy = 5f
            speed = 12f
            bearing = 90f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        locations.simulateLocation(gps)
        val network = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 50.451
            longitude = 30.521
            accuracy = 300f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        locations.simulateLocation(network)
        waitFor("GPS fix") { positioning.lastJudged != null }
        assertEquals(FixSource.GPS, positioning.lastJudged!!.fix.source)
        waitFor("network fix") { positioning.lastNet != null }

        // Idle (screen on, no trip): the barometer and step counter are released.
        hub.configure(PowerProfile.BALANCED, navigating = false)
        waitFor("barometer released") { log.any { it == "barometer off" } }
    }

    @Test
    fun locationSwitchIsReportedAndRawAccelerometerReplacesAMissingRotationSensor() {
        sensors.removeSensor(installed.getValue(Sensor.TYPE_ROTATION_VECTOR))
        val locations = shadowOf(context.getSystemService(LocationManager::class.java))
        locations.setLocationEnabled(false)
        assertFalse(runBlocking { hub.checkLocationEnabled() })
        locations.setLocationEnabled(true)
        assertTrue(runBlocking { hub.checkLocationEnabled() })

        hub.start()
        hub.configure(PowerProfile.BALANCED, navigating = true)
        waitFor("accelerometer") { sensors.listeners.isNotEmpty() }
        repeat(5) { send(Sensor.TYPE_ACCELEROMETER, 0.3f, 0.2f, 9.81f) }
        waitFor("fallback IMU sample") { imu.isNotEmpty() }
        assertEquals("no rotation sensor: no compass heading", null, imu.last().headingDeg)
        hub.stop()
    }
}
