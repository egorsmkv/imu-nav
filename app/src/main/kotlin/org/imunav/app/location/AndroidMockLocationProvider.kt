package org.imunav.app.location

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.edit
import org.imunav.core.location.MockLocationProvider
import org.imunav.core.location.MockLocationSample

/** GPS-only export leaves network/cell positioning available as an independent navigation input. */
internal class AndroidMockLocationProvider(context: Context, private val prefs: SharedPreferences) : MockLocationProvider {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val packageName = context.packageName

    @Suppress("DEPRECATION") // The check and provider overload also work on our Android 8 minimum.
    override fun isAllowed(): Boolean = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

    @Suppress("DEPRECATION") // Criteria constants and this overload support Android 8–11 as well.
    override fun install() {
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, false, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        // Synchronous on the IO worker: retain ownership across process death for startup cleanup.
        prefs.edit(commit = true) { putBoolean(INSTALLED, true) }
    }

    override fun publish(sample: MockLocationSample) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = sample.point.lat
            longitude = sample.point.lon
            accuracy = sample.accuracyM
            speed = sample.speedMps
            bearing = sample.bearingDeg
            elapsedRealtimeNanos = sample.elapsedMs * 1_000_000L
            time = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sample.elapsedMs)
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    override fun remove() {
        try {
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: IllegalArgumentException) {
            // Android 8–11 throw when a previously owned test provider has already disappeared.
        }
        prefs.edit(commit = true) { putBoolean(INSTALLED, false) }
    }

    companion object {
        const val INSTALLED = "provider_installed"
    }
}
