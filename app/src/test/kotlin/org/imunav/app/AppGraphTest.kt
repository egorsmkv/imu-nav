package org.imunav.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.imunav.app.power.MapFrameRate
import org.imunav.app.power.PowerMode
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** The whole app graph runs on a host JVM: planning, navigation with a real offline pack, settings. */
@RunWith(RobolectricTestRunner::class)
class AppGraphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val temporary = TemporaryFolder().apply { create() }

    @Before
    fun grantLocation() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Let main-thread and background work settle until [done] holds (fails after a generous timeout). */
    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun graphWithPack(): AppGraph {
        val graph = AppGraph(context)
        val zip = TestPacks.build(temporary.newFolder("pack"))
        graph.offlineRouting.setAllowOnline(false)
        graph.offlineRouting.importZip { zip.inputStream() }
        waitFor("pack import") { graph.offlineRouting.status.value.loaded && graph.offlineRouting.status.value.busy == null }
        return graph
    }

    @Test
    fun planningStopsAndDestinationPreviewsARouteThroughTheStop() {
        val graph = graphWithPack()
        graph.setManualStart(TestPacks.START, "Start")
        graph.setDestination(TestPacks.DESTINATION, "Destination")
        waitFor("route preview") { graph.ui.value.previewRoute != null }
        val direct = checkNotNull(graph.ui.value.previewRoute).length

        graph.setWaypoint(null, GeoPoint(50.463, 30.500), "Side street")
        waitFor("preview with stop") { graph.ui.value.previewRoute?.length?.let { it > direct + 100 } == true }
        assertEquals("Side street", graph.ui.value.waypoints.single().label)
        graph.setWaypoint(0, TestPacks.TURN, "Junction")
        assertEquals("Junction", graph.ui.value.waypoints.single().label)
        graph.removeWaypoint(0)
        graph.removeWaypoint(5)
        assertTrue(graph.ui.value.waypoints.isEmpty())
        repeat(MAX_ROUTE_STOPS + 1) { graph.setWaypoint(null, TestPacks.TURN) }
        assertEquals("never more than the allowed number of stops", MAX_ROUTE_STOPS, graph.ui.value.waypoints.size)

        // A saved route replaces both endpoints and forgets the stops.
        val saved = SavedRoute("r", "Saved", BookmarkOrigin.Fixed(BookmarkPoint(TestPacks.START, "Home")), BookmarkPoint(TestPacks.DESTINATION, "Work"), TravelMode.CAR)
        assertTrue(graph.openBookmark(saved))
        assertTrue(graph.ui.value.waypoints.isEmpty())
        assertEquals("Work", graph.ui.value.destinationLabel)
        assertTrue(graph.useBookmark(SavedPlace("p", "Shop", BookmarkPoint(TestPacks.TURN)), asStart = false))
        assertEquals(TestPacks.TURN, graph.ui.value.mapFocusRequest)
        graph.clearMapFocusRequest()
        assertNull(graph.ui.value.mapFocusRequest)

        graph.setManualStart(null)
        graph.setDestination(null)
        assertNull(graph.ui.value.destination)
    }

    @Test
    fun navigationRunsTicksAcceptsAHandPlacedPositionAndStops() {
        val graph = graphWithPack()
        // The phone screen is open, as MainActivity reports: the full UI state is published.
        graph.setPhoneVisible(true)
        graph.setManualStart(TestPacks.START, "Start")
        graph.setDestination(TestPacks.DESTINATION)
        graph.setWaypoint(null, TestPacks.TURN, "Junction")
        var started = false
        graph.startNavigation { started = true }
        waitFor("navigation start") { graph.engine.state.active && !graph.ui.value.startingNavigation }
        assertTrue(started)
        assertEquals(listOf(TestPacks.TURN), graph.engine.state.waypoints)
        graph.onNavigationServiceStarted()

        // The planning API is frozen while a trip runs.
        graph.setDestination(GeoPoint(50.0, 30.0))
        graph.setWaypoint(null, GeoPoint(50.0, 30.0))
        assertEquals(TestPacks.DESTINATION, graph.ui.value.destination)

        repeat(6) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            graph.tick()
        }
        graph.correctPosition(TestPacks.TURN)
        assertTrue("the marker jumped to the junction", graph.engine.state.s > 1000.0)
        graph.setSimulateGpsLoss(true)
        assertTrue(graph.ui.value.simulateGpsLoss)
        graph.setSimulateGpsLoss(false)
        graph.refresh()

        graph.stopNavigation()
        assertFalse(graph.engine.state.active)
        // A trip stopped on the way shows the remaining route again.
        waitFor("preview after stop") { graph.ui.value.previewRoute != null }
        graph.onNavigationServiceStopped()
        graph.onNavigationServiceFailure()
        assertNotNull(graph.ui.value.error)
        graph.clearError()
        assertNull(graph.ui.value.error)
    }

    @Test
    fun withoutPositionOrPermissionNavigationExplainsWhy() {
        val graph = AppGraph(context)
        graph.offlineRouting.setAllowOnline(false)
        graph.setDestination(TestPacks.DESTINATION)
        graph.startNavigation()
        assertEquals(context.getString(R.string.error_no_position), graph.ui.value.error)

        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        graph.clearError()
        graph.startNavigation()
        assertEquals(context.getString(R.string.car_setup), graph.ui.value.error)
        assertNull(graph.currentPosition())
    }

    @Test
    fun settingsAreAppliedAndRemembered() {
        val graph = AppGraph(context)
        graph.setTravelMode(TravelMode.FOOT)
        assertEquals(TravelMode.FOOT, graph.travelMode.value)
        graph.setNavigationMethod(NavigationMethod.DEAD_RECKONING)
        graph.setNavigationEstimator(NavigationEstimator.NATIVE_KALMAN)
        graph.setInertialExperiment(true)
        graph.setTerrainMatch(false)
        assertFalse(graph.tuning.value.terrainMatch)
        graph.setMapStart(MapStartMode.FIXED, GeoPoint(49.84, 24.03))
        assertEquals(GeoPoint(49.84, 24.03), graph.mapStartFixed.value)
        graph.setVoiceEnabled(false)
        assertFalse(graph.voiceEnabled.value)
        graph.setTheme(AppTheme.DARK)
        assertEquals(AppTheme.DARK, graph.theme.value)
        graph.setLanguage(AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, graph.language.value)
        for (mode in PowerMode.entries) graph.setPowerMode(mode)
        graph.setMapFrameRate(MapFrameRate.FPS_15)
        assertEquals(MapFrameRate.FPS_15, graph.mapFrameRate.value)
        graph.setKeepScreenOn(false)
        assertFalse(graph.keepScreenOn.value)

        graph.setPhoneVisible(true)
        graph.setCarVisible("car-1", true)
        graph.disconnectCar("car-1")
        graph.setPhoneVisible(false)
        graph.startSensing()
        graph.stopSensing()
        ShadowLooper.idleMainLooper()

        val restored = AppGraph(context)
        assertEquals(TravelMode.FOOT, restored.travelMode.value)
        assertFalse(restored.voiceEnabled.value)
        graph.setLanguage(AppLanguage.SYSTEM)
        graph.setTheme(AppTheme.SYSTEM)
    }
}
