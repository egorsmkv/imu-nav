package org.imunav.app.sensors

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.imu.eskf.Attitude
import org.imunav.core.imu.eskf.InertialKind
import org.imunav.core.imu.eskf.InertialSample
import org.imunav.core.imu.eskf.Vector3

/** Main-thread raw capture for the opt-in experiment; keeps legacy turn-detection samples untouched. */
internal class InertialCapture(private val hub: PositioningHub) {
    private var declinationRad: Double? = null
    var enabled = false
        set(value) {
            if (field != value) declinationRad = null
            field = value
        }

    /** Preserve each event's own timestamp and device axes; Android's accelerometer includes gravity. */
    fun onSensor(event: SensorEvent) {
        if (!enabled) return
        val kind = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> InertialKind.ACCELEROMETER
            Sensor.TYPE_GYROSCOPE -> InertialKind.GYROSCOPE
            Sensor.TYPE_ROTATION_VECTOR -> InertialKind.ATTITUDE
            else -> return
        }
        val sample = if (kind == InertialKind.ATTITUDE) {
            orientation(event) ?: return
        } else {
            InertialSample(event.timestamp, kind, Vector3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
        }
        hub.onInertial(sample, SystemClock.elapsedRealtime())
    }

    /**
     * Android rotation vectors point to magnetic north. Fix declination once from a fresh GOOD GPS
     * anchor; record the resulting true-ENU quaternion so replay needs no geomagnetic model or future
     * GPS. Positive east declination is a negative (clockwise) rotation about ENU up.
     */
    private fun orientation(event: SensorEvent): InertialSample? {
        if (declinationRad == null) {
            val fix = hub.lastGood ?: return null
            if (event.timestamp / NS_PER_MS - fix.elapsedMs !in 0..MAX_ANCHOR_AGE_MS) return null
            declinationRad = Math.toRadians(GeomagneticField(fix.lat.toFloat(), fix.lon.toFloat(), (fix.altitudeM ?: 0.0).toFloat(), fix.timeMs).declination.toDouble())
        }
        val values = FloatArray(4)
        SensorManager.getQuaternionFromVector(values, event.values)
        val normSquared = values.sumOf { it.toDouble() * it }
        if (!normSquared.isFinite() || normSquared !in 0.9..1.1) return null
        val magnetic = Attitude(values[0].toDouble(), values[1].toDouble(), values[2].toDouble(), values[3].toDouble())
        val trueNorth = (Attitude.exp(Vector3(0.0, 0.0, -requireNotNull(declinationRad))) * magnetic).normalized()
        return InertialSample(event.timestamp, InertialKind.ATTITUDE, Vector3(trueNorth.x, trueNorth.y, trueNorth.z), trueNorth.w)
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
        private const val MAX_ANCHOR_AGE_MS = 2_000L
    }
}
