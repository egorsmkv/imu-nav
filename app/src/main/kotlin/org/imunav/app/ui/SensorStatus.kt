package org.imunav.app.ui

import android.content.res.Resources
import org.imunav.app.R
import org.imunav.app.sensors.NavigationSensor

/** Resolve text from the current UI locale so switching language also updates sensor warnings. */
internal fun sensorStatusLabel(resources: Resources, missing: List<NavigationSensor>): String {
    if (missing.isEmpty()) return resources.getString(R.string.diag_sensors_full)
    val names = missing.joinToString(", ") { sensor ->
        resources.getString(
            when (sensor) {
                NavigationSensor.GYROSCOPE -> R.string.sensor_gyroscope
                NavigationSensor.ROTATION_VECTOR -> R.string.sensor_rotation_vector
                NavigationSensor.COMPASS -> R.string.sensor_compass
            },
        )
    }
    return resources.getString(R.string.diag_sensors_missing, names)
}
