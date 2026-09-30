package org.imunav.app.sensors

import android.location.Location
import android.location.LocationListener
import android.os.Bundle

/**
 * Forwards fixes while explicitly implementing the callbacks required on Android 8–10.
 * A LocationListener lambda only implements onLocationChanged: provider callbacks have defaults
 * on modern Android, but throw AbstractMethodError on older phones when Location is switched off.
 * Provider state is read separately by SensorHub; changing it must not invent a positioning fix.
 */
internal class FixLocationListener(private val onFix: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onFix(location)

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Suppress("OVERRIDE_DEPRECATION") // Android 8–10 still require this legacy callback to be implemented.
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
