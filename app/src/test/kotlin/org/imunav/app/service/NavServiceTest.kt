package org.imunav.app.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.imunav.app.R
import org.imunav.app.TestGraphApp
import org.imunav.app.graph
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
import org.imunav.core.route.ManeuverType
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The foreground service shows the next maneuver while navigating and stops itself afterwards. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGraphApp::class)
class NavServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = LocalProjection(GeoPoint(50.45, 30.52))
    private val route = Route(
        (0..2000 step 20).map { projection.toGeo(0.0, it.toDouble()) },
        listOf(Step(ManeuverType.DEPART, null, "Khreshchatyk", 2000.0, 150.0, 0), Step(ManeuverType.ARRIVE, null, "", 0.0, 0.0, 100)),
        150.0,
    )

    @Before
    fun permissions() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Let the engine loop run for [ms] of virtual main-looper time. */
    private fun idle(ms: Long) {
        repeat((ms / STEP_MS).toInt()) {
            ShadowLooper.idleMainLooper(STEP_MS, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    private fun notificationText(): String? = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        .lastOrNull()?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    @Test
    fun activeNavigationKeepsTheServiceInForegroundWithGuidance() {
        val app = context.graph
        app.engine.start(route, route.geometry.last(), emptyList(), SystemClock.elapsedRealtime())
        val controller = Robolectric.buildService(NavService::class.java).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertEquals(1, shadow.lastForegroundNotificationId)
        assertEquals(context.getString(R.string.notification_title), shadow.lastForegroundNotification.extras.getString(NotificationCompat.EXTRA_TITLE))

        idle(3_000)
        val text = checkNotNull(notificationText())
        assertTrue("distance and instruction: $text", text.contains(" · "))
        assertFalse(shadow.isStoppedBySelf)

        // A second start request reuses the running loop.
        controller.startCommand(0, 2)
        app.engine.stop()
        idle(1_500)
        assertTrue("navigation ended: the service stops itself", shadow.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun stopActionAndIdleStartEndTheService() {
        val stopped = Robolectric.buildService(NavService::class.java, Intent().setAction("stop")).create().startCommand(0, 1)
        assertTrue(shadowOf(stopped.get()).isStoppedBySelf)
        stopped.destroy()

        val idleStart = Robolectric.buildService(NavService::class.java).create().startCommand(0, 1)
        val shadow = shadowOf(idleStart.get())
        assertEquals(context.getString(R.string.position_sharing_title), shadow.lastForegroundNotification.extras.getString(NotificationCompat.EXTRA_TITLE))
        idle(500)
        assertTrue("nothing to navigate or share", shadow.isStoppedBySelf)
        idleStart.destroy()

        NavService.start(context)
        assertEquals(NavService::class.java.name, shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedService.component?.className)
        NavService.stop(context)
    }
}

private const val STEP_MS = 100L
