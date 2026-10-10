package org.imunav.app.location

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.imunav.app.graph
import org.imunav.app.service.NavService
import org.imunav.core.geo.GeoPoint
import org.imunav.core.location.MockLocationStatus
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercises the asynchronous production worker and the actual foreground navigation service. */
@RunWith(AndroidJUnit4::class)
class MockLocationSharingTest {
    private val device = MockLocationDevice()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = device.context.getSharedPreferences("mock_location_sharing_test", Context.MODE_PRIVATE)
    private lateinit var sharing: MockLocationSharing
    private val guidance = GuidanceState(active = true, position = GeoPoint(48.125, 28.25), source = PositionSource.DR, uncertaintyM = 75.0)

    @Before
    fun setUp() {
        device.prepare()
        prefs.edit(commit = true) { clear() }
        device.onMain { sharing = MockLocationSharing(mockLocationTestContext(device.context, "mock_location_sharing_test"), scope) }
    }

    @After
    fun tearDown() {
        device.allowMock(true)
        device.onMain {
            sharing.setEnabled(false)
            device.context.graph.mockLocationSharing.setEnabled(false)
            device.context.graph.stopNavigation()
            NavService.stop(device.context)
        }
        awaitStatus(MockLocationStatus.OFF)
        device.await("navigation GPS cleanup") { device.context.graph.mockLocationSharing.status.value == MockLocationStatus.OFF }
        scope.cancel()
        prefs.edit(commit = true) { clear() }
        device.assertOriginalProvider()
        device.restore()
    }

    @Test
    fun offByDefaultThenStopsOnIdleInvalidPositionAndExplicitStop() {
        assertFalse(sharing.enabled.value)
        device.onMain { sharing.update(guidance) }
        device.assertOriginalProvider()
        device.onMain { sharing.setEnabled(true) }
        awaitStatus(MockLocationStatus.ACTIVE)
        val invalid = listOf(guidance.copy(active = false), guidance.copy(position = null), guidance.copy(position = GeoPoint(Double.NaN, 28.25)))
        invalid.forEach { state ->
            device.onMain { sharing.update(state) }
            awaitStatus(MockLocationStatus.WAITING)
            device.assertOriginalProvider()
            device.onMain { sharing.update(guidance) }
            awaitStatus(MockLocationStatus.ACTIVE)
        }
        device.onMain { sharing.stop() }
        awaitStatus(MockLocationStatus.WAITING)
        assertTrue(sharing.enabled.value)
        device.assertOriginalProvider()
        device.onMain { sharing.setEnabled(false) }
        awaitStatus(MockLocationStatus.OFF)
    }

    @Test
    fun settingsRefreshReportsPermissionAndRecoversAfterAccessIsGranted() {
        device.allowMock(false)
        device.onMain { sharing.setEnabled(true) }
        awaitStatus(MockLocationStatus.PERMISSION_REQUIRED)
        device.assertOriginalProvider()
        device.allowMock(true)
        device.onMain { sharing.refresh() }
        awaitStatus(MockLocationStatus.WAITING)
        device.onMain { sharing.update(guidance) }
        awaitStatus(MockLocationStatus.ACTIVE)
    }

    @Test
    fun foregroundServiceKeepsPublishingWithActivityStoppedAndCleansUpOnDestruction() {
        val graph = device.context.graph
        ActivityScenario.launch(MockLocationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val route = Route(listOf(GeoPoint(48.125, 28.25), GeoPoint(48.225, 28.25)), emptyList(), 1000.0)
                graph.engine.start(route, route.geometry.last(), nowMs = SystemClock.elapsedRealtime(), startAccuracyM = 75.0)
                graph.mockLocationSharing.setEnabled(true)
                NavService.start(activity)
            }
            device.await("foreground GPS sharing") { graph.mockLocationSharing.status.value == MockLocationStatus.ACTIVE }
            MockGpsObserver(device).use { observer ->
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(graph.uiVisible)
                val backgroundSince = SystemClock.elapsedRealtime()
                val fix = observer.fresh(afterMs = backgroundSince)
                assertTrue(fix.latitude in 48.125..48.225)
                assertTrue(abs(fix.longitude - 28.25) < 0.00001)
                assertTrue("Service must publish a fresh background fix", fix.elapsedRealtimeNanos / 1_000_000L >= backgroundSince)
                device.onMain { NavService.stop(device.context) }
                device.await("service destruction GPS cleanup") { graph.mockLocationSharing.status.value == MockLocationStatus.WAITING }
                device.assertOriginalProvider()
            }
        }
    }

    private fun awaitStatus(status: MockLocationStatus) = device.await("GPS status $status (current ${sharing.status.value})") { sharing.status.value == status }
}
