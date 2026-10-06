package org.imunav.app.sensors

import android.hardware.Sensor

/** Stable sensor identities let the UI translate availability without changing diagnostic logs. */
enum class NavigationSensor(val androidType: Int, val logName: String) {
    GYROSCOPE(Sensor.TYPE_GYROSCOPE, "gyroscope"),
    ROTATION_VECTOR(Sensor.TYPE_ROTATION_VECTOR, "rotation vector"),
    COMPASS(Sensor.TYPE_MAGNETIC_FIELD, "compass"),
}
