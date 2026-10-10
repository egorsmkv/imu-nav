package org.imunav.app.location

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Changes only a disposable emulator, restoring mock AppOps and the global location switch. */
internal class MockLocationDevice {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val manager: LocationManager = context.getSystemService(LocationManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private var originalMockMode = AppOpsManager.MODE_DEFAULT
    private var originalLocationMode = "0"
    private var originalProvider: List<Any>? = null

    init {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "GPS tests require a disposable Android Emulator" }
    }

    @Suppress("DEPRECATION") // checkOpNoThrow and LocationProvider are available on API 28.
    fun prepare() {
        originalMockMode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        originalLocationMode = shell("settings get secure location_mode")
        shell("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("settings put secure location_mode 3")
        allowMock(true)
        originalProvider = providerProperties()
        check(originalProvider != null) { "Emulator has no original GPS provider" }
    }

    fun allowMock(allowed: Boolean) {
        shell("appops set ${context.packageName} android:mock_location ${if (allowed) "allow" else "deny"}")
    }

    fun restore() {
        allowMock(true)
        // Covers test failures before their own cleanup. No providers other than GPS are changed.
        runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        shell("settings put secure location_mode $originalLocationMode")
        val mode = when (originalMockMode) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            else -> "default"
        }
        shell("appops set ${context.packageName} android:mock_location $mode")
    }

    fun assertOriginalProvider() = assertEquals("GPS must return to its original platform properties", originalProvider, providerProperties())

    @Suppress("DEPRECATION") // Legacy LocationProvider properties let API 28 and 31 share assertions.
    private fun providerProperties(): List<Any>? = manager.getProvider(LocationManager.GPS_PROVIDER)?.let {
        listOf(it.requiresSatellite(), it.requiresNetwork(), it.requiresCell(), it.supportsAltitude(), it.supportsSpeed(), it.supportsBearing(), it.powerRequirement, it.accuracy)
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Instrumentation alone does not make a while-in-use GPS subscriber eligible on Android 12+. */
    @Suppress("DEPRECATION") // AppOps check is available on Android 9.
    fun withForeground(action: () -> Unit) {
        ActivityScenario.launch(MockLocationTestActivity::class.java).use {
            await("visible subscriber's fine location access") {
                appOps.noteOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
            }
            action()
        }
    }

    fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        assertTrue("Timed out: $description", predicate())
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** A real GPS subscriber with a bounded wait; never republishes to make a dropped fix pass. */
internal class MockGpsObserver(private val device: MockLocationDevice) : AutoCloseable {
    private val thread = HandlerThread("mock-gps-observer").apply { start() }
    private val locations = LinkedBlockingQueue<Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            locations.offer(Location(location))
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Suppress("DEPRECATION") // Required for LocationListener on API 28, before default methods.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    init {
        device.manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, thread.looper)
    }

    fun next(point: GeoPoint, afterMs: Long = 0): Location = nextMatching {
        it.latitude == point.lat && it.longitude == point.lon && it.elapsedRealtimeNanos / 1_000_000L >= afterMs
    }

    fun fresh(afterMs: Long): Location = nextMatching { it.elapsedRealtimeNanos / 1_000_000L >= afterMs }

    private fun nextMatching(matches: (Location) -> Boolean): Location {
        val deadline = SystemClock.elapsedRealtime() + MockLocationDevice.TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val location = locations.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (matches(location)) return location
        }
        error("No GPS fix matched the expected synthetic test sample")
    }

    override fun close() {
        device.manager.removeUpdates(listener)
        thread.quitSafely()
        thread.join(MockLocationDevice.TIMEOUT_MS)
        check(!thread.isAlive) { "GPS listener thread did not stop" }
    }
}
