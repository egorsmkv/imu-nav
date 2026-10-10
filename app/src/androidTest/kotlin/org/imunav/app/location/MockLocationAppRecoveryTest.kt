package org.imunav.app.location

import android.content.Context
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.imunav.app.graph
import org.imunav.app.service.NavService
import org.imunav.core.geo.GeoPoint
import org.imunav.core.location.MockLocationStatus
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Host-orchestrated stages, excluded from the ordinary package suite. The host force-stops the
 * entire target between these methods; each runs under a fresh instrumentation invocation.
 */
@RunWith(AndroidJUnit4::class)
class MockLocationAppRecoveryTest {
    private val device = MockLocationDevice()
    private val prefs = device.context.getSharedPreferences("mock_location_app_recovery_test", Context.MODE_PRIVATE)

    @Test
    @Suppress("DEPRECATION") // Original GPS properties can also be checked on Android 9.
    fun seedPersistedTripAndLeaveGpsActive() {
        device.prepare()
        val graph = device.context.graph
        prefs.edit(commit = true) {
            putInt("pid", Process.myPid())
            putBoolean("satellite", requireNotNull(device.manager.getProvider(LocationManager.GPS_PROVIDER)).requiresSatellite())
        }
        ActivityScenario.launch(MockLocationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val route = Route(listOf(GeoPoint(48.125, 28.25), GeoPoint(48.225, 28.25)), emptyList(), 1000.0)
                graph.setNavigationMethod(NavigationMethod.DEAD_RECKONING)
                graph.engine.start(route, route.geometry.last(), nowMs = SystemClock.elapsedRealtime(), startAccuracyM = 75.0)
                graph.trips.begin(route, route.geometry.last(), emptyList(), 75.0)
                graph.mockLocationSharing.setEnabled(true)
                NavService.start(activity)
            }
            device.await("GPS before whole-app death") { graph.mockLocationSharing.status.value == MockLocationStatus.ACTIVE }
            val activeTrip = File(device.context.filesDir, "trips/active.json")
            device.await("durable saved trip") { activeTrip.isFile && activeTrip.length() > 0 }
            assertTrue(graph.trips.active)
            assertTrue(device.context.getSharedPreferences("mock_location", Context.MODE_PRIVATE).getBoolean(AndroidMockLocationProvider.INSTALLED, false))
        }
        // Intentionally retain trip, provider and opt-in. The host kills the target, then verifies.
    }

    @Test
    @Suppress("DEPRECATION") // Compare original GPS properties after the restored trip ends on API 28.
    fun relaunchRestoresSavedTripAndPublishesFreshGps() {
        device.prepare()
        val graph = device.context.graph
        try {
            assertNotEquals("Whole application must run in a new process", prefs.getInt("pid", -1), Process.myPid())
            assertFalse("Background process creation must not activate a trip", graph.engine.state.active)
            assertTrue("System GPS opt-in must survive", graph.mockLocationSharing.enabled.value)
            ActivityScenario.launch(MockLocationTestActivity::class.java).use { scenario ->
                // Explicit foreground relaunch is tested; force-stop does not promise automatic restart.
                scenario.onActivity { NavService.start(it) }
                device.await("foreground saved-trip installation") { graph.trips.active && graph.engine.state.active }
                MockGpsObserver(device).use { observer ->
                    val relaunchedAt = SystemClock.elapsedRealtime()
                    val fix = observer.fresh(relaunchedAt)
                    assertTrue(fix.latitude in 48.125..48.225)
                    assertEquals(28.25, fix.longitude, 0.00001)
                    device.await("restored GPS publication") { graph.mockLocationSharing.status.value == MockLocationStatus.ACTIVE }
                }
            }
        } finally {
            device.onMain {
                graph.mockLocationSharing.setEnabled(false)
                graph.stopNavigation()
                NavService.stop(device.context)
            }
            device.await("restored trip cleanup") { graph.mockLocationSharing.status.value == MockLocationStatus.OFF }
            assertFalse(graph.trips.active)
            assertEquals(prefs.getBoolean("satellite", true), requireNotNull(device.manager.getProvider(LocationManager.GPS_PROVIDER)).requiresSatellite())
            prefs.edit(commit = true) { clear() }
            device.restore()
        }
    }
}
