package org.imunav.app.location

import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.imunav.app.graph
import org.imunav.app.service.NavService
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.location.NavigationMirrorEncoder
import org.imunav.core.route.Route
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A synthetic route exercises service-owned TCP output with the screen off, without mock GPS export. */
@RunWith(AndroidJUnit4::class)
class PositionSharingLifecycleTest {
    private val device = MockLocationDevice()

    @Before
    fun prepare() {
        device.prepare()
        device.onMain { device.context.graph.mockLocationSharing.setEnabled(false) }
    }

    @After
    fun cleanup() {
        device.shell("input keyevent KEYCODE_WAKEUP")
        device.onMain {
            device.context.graph.stopNavigation()
            device.context.graph.positionSharing.stop()
            NavService.stop(device.context)
        }
        device.restore()
    }

    @Test
    fun servicePublishesFreshRmcInBackgroundAndStopDoesNotAuthorizeTheNextTrip() {
        val graph = device.context.graph
        val sharing = graph.positionSharing
        assertFalse(sharing.status.value.enabled)
        sharing.refreshAddresses()
        device.await("emulator private interface") { sharing.status.value.addresses.isNotEmpty() }
        val address = sharing.status.value.addresses.first()
        ActivityScenario.launch(MockLocationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val route = Route(listOf(GeoPoint(48.125, 28.25), GeoPoint(48.225, 28.25)), emptyList(), 1000.0)
                graph.engine.start(route, route.geometry.last(), nowMs = SystemClock.elapsedRealtime(), startAccuracyM = 75.0)
                sharing.start(PositionSharingMode.LOCAL, address, navigating = true)
                NavService.start(activity)
            }
            device.await("local position stream") { sharing.status.value.local.phase == PositionSharingPhase.ACTIVE }
            Socket(address, 10110).use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("\$GPRMC,"))
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(graph.uiVisible)
                device.shell("input keyevent KEYCODE_SLEEP")
                val power = device.context.getSystemService(PowerManager::class.java)
                device.await("screen off") { !power.isInteractive }
                val backgroundTime = LocalTime.now(ZoneOffset.UTC)
                var fresh = false
                repeat(8) {
                    val line = reader.readLine()
                    assertTrue(line.contains(",,,E*"))
                    val time = LocalTime.parse(line.split(',')[1], DateTimeFormatter.ofPattern("HHmmss.SSS"))
                    if (time.isAfter(backgroundTime)) fresh = true
                }
                assertTrue("Foreground service must send a newly sampled screen-off position", fresh)
                device.onMain { NavService.stop(device.context) }
                device.await("service teardown disables sharing") { !sharing.status.value.enabled }
                // Drain any already transmitted record before asserting connection closure.
                while (reader.readLine() != null) Unit
                assertEquals(0, sharing.status.value.local.receivers)
            }
        }
    }

    @Test
    fun standaloneSharingRunsWithScreenOffWithoutStartingATrip() {
        val graph = device.context.graph
        val sharing = graph.positionSharing
        sharing.refreshAddresses()
        device.await("emulator private interface") { sharing.status.value.addresses.isNotEmpty() }
        val address = sharing.status.value.addresses.first()
        ActivityScenario.launch(MockLocationTestActivity::class.java).use { scenario ->
            val publisher = publishIdleFixes()
            try {
                scenario.onActivity { graph.startPositionSharing(PositionSharingMode.LOCAL, address, shareNavigation = true) }
                device.await("standalone listener") { sharing.status.value.local.endpoint != null }
                assertFalse(graph.engine.state.active)
                assertFalse(sharing.status.value.shareNavigation)
                device.await("idle position stream") { sharing.status.value.local.phase == PositionSharingPhase.ACTIVE }
                Socket(address, 10110).use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    assertTrue(reader.readLine().startsWith("\$GPRMC,"))
                    socket.getOutputStream().write(NavigationMirrorEncoder.HANDSHAKE.toByteArray())
                    assertEquals("hello", JSONObject(reader.readLine()).getString("type"))
                    assertTrue(JSONObject(reader.readLine()).getJSONObject("state").isNull("guidance"))
                    scenario.moveToState(Lifecycle.State.CREATED)
                    val backgroundMs = SystemClock.elapsedRealtime()
                    device.shell("input keyevent KEYCODE_SLEEP")
                    val power = device.context.getSystemService(PowerManager::class.java)
                    device.await("screen off") { !power.isInteractive }
                    var fresh = false
                    repeat(8) {
                        val state = JSONObject(reader.readLine()).getJSONObject("state")
                        if (state.getLong("elapsed_ms") >= backgroundMs) fresh = true
                        assertTrue(state.isNull("guidance"))
                        assertFalse(graph.engine.state.active)
                    }
                    assertTrue("Idle fixes must keep updating with the screen off", fresh)
                    device.onMain { sharing.stop() }
                    while (reader.readLine() != null) Unit
                    assertFalse(sharing.status.value.enabled)
                }
            } finally {
                publisher.cancel()
            }
        }
    }

    /** Synthetic hub input tests the sharing output without weakening Android's mock-GPS rejection. */
    private fun publishIdleFixes(): Job = CoroutineScope(Dispatchers.Main).launch {
        val graph = device.context.graph
        while (isActive) {
            graph.hub.onFix(RawFix(FixSource.CELL, System.currentTimeMillis(), SystemClock.elapsedRealtime(), 48.125, 28.25, accuracyM = 75f))
            delay(graph.powerProfile.value.positionSharingMs)
        }
    }

    @Test
    fun mirroredGuidanceKeepsUpdatingWithTheScreenOffAndDetailsCanBeWithdrawn() {
        val graph = device.context.graph
        val sharing = graph.positionSharing
        sharing.refreshAddresses()
        device.await("emulator private interface") { sharing.status.value.addresses.isNotEmpty() }
        val address = sharing.status.value.addresses.first()
        ActivityScenario.launch(MockLocationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val route = Route(listOf(GeoPoint(48.125, 28.25), GeoPoint(48.225, 28.25)), emptyList(), 1000.0)
                graph.engine.start(route, route.geometry.last(), nowMs = SystemClock.elapsedRealtime(), startAccuracyM = 75.0)
                sharing.start(PositionSharingMode.LOCAL, address, navigating = true, shareNavigation = true)
                NavService.start(activity)
            }
            device.await("mirror sharing active") { sharing.status.value.local.phase == PositionSharingPhase.ACTIVE }
            Socket(address, 10110).use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("\$GPRMC,"))
                socket.getOutputStream().write(NavigationMirrorEncoder.HANDSHAKE.toByteArray())
                assertEquals("hello", JSONObject(reader.readLine()).getString("type"))
                scenario.moveToState(Lifecycle.State.CREATED)
                device.shell("input keyevent KEYCODE_SLEEP")
                val backgroundMs = SystemClock.elapsedRealtime()
                var fresh = false
                repeat(12) {
                    val frame = JSONObject(reader.readLine())
                    if (frame.getString("type") == "state") {
                        val state = frame.getJSONObject("state")
                        val guidance = state.getJSONObject("guidance")
                        assertTrue(guidance.has("gps_state"))
                        assertTrue(guidance.getLong("route_revision") > 0)
                        if (state.getLong("elapsed_ms") > backgroundMs) fresh = true
                    }
                }
                assertTrue("Mirror must receive fresh engine/trust output while Android UI is hidden", fresh)
                device.onMain { sharing.setShareNavigation(false) }
                while (reader.readLine() != null) Unit
            }
            device.await("position-only stream restarted") { !sharing.status.value.shareNavigation && sharing.status.value.local.phase == PositionSharingPhase.ACTIVE }
            Socket(address, 10110).use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("\$GPRMC,"))
                socket.getOutputStream().write(NavigationMirrorEncoder.HANDSHAKE.toByteArray())
                assertEquals("hello", JSONObject(reader.readLine()).getString("type"))
                val state = JSONObject(reader.readLine()).getJSONObject("state")
                assertTrue(state.isNull("guidance"))
            }
        }
    }
}
