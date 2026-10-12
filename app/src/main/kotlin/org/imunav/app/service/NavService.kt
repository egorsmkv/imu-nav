package org.imunav.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.imunav.app.R
import org.imunav.app.graph
import org.imunav.app.location.PositionSharingPhase
import org.imunav.app.ui.MainActivity
import org.imunav.app.ui.formatDistance
import org.imunav.app.ui.formatDuration
import org.imunav.app.ui.instructionLine
import org.imunav.core.location.MockLocationStatus
import org.imunav.core.location.NavigationMirrorInput
import org.imunav.core.location.NavigationPositionSample
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.util.PeriodicDeadline
import org.imunav.core.util.RenewableLease
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps navigation running while the screen is off or another app is in front.
 *
 * Android stops background apps from using location and freezes their code. A *foreground
 * service* is the official way around that: it shows a permanent notification (here: the next
 * maneuver) and in return may keep using GPS and sensors. The service also:
 *  - holds a partial wake lock so the CPU keeps running the 2 Hz engine loop with the screen off,
 *  - calls [AppGraph.tick] every [NavigationEngine.TICK_MS] until navigation ends.
 *
 * Start with [start] for navigation or explicit position sharing; stop when neither needs location.
 */
class NavService : LifecycleService() {
    /** The engine loop; null until started. */
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLease = RenewableLease(MAX_WAKE_LOCK_MS, WAKE_LOCK_RENEW_MS)
    private val wakeControl = Executors.newSingleThreadExecutor()

    /** The notification text last posted (to skip identical updates). */
    private var lastText: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be called within a few seconds of startForegroundService(), or Android kills the app.
        val serviceType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("…"), serviceType)
        } catch (_: SecurityException) {
            graph.onNavigationServiceFailure()
            stopSelf()
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            graph.onNavigationServiceFailure()
            stopSelf()
            return START_NOT_STICKY
        }
        graph.onNavigationServiceStarted()
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imunav:nav")
                .apply { setReferenceCounted(false) } // Renew timed leases on the control worker; release is ordered after acquisition.
        }
        wakeLease.renewIfDue(SystemClock.elapsedRealtime()) { timeout ->
            val lock = wakeLock
            wakeControl.execute { lock?.acquire(timeout) }
        }
        val app = graph
        if (loop == null) {
            loop = lifecycleScope.launch {
                app.awaitTripRestoration()
                if (app.engine.state.active || app.positionSharing.status.value.enabled) app.startSensing()
                var tickCount = 0
                val cadence = PeriodicDeadline(SystemClock.elapsedRealtime(), NavigationEngine.TICK_MS)
                while (isActive && (app.engine.state.active || app.positionSharing.status.value.enabled)) {
                    wakeLease.renewIfDue(SystemClock.elapsedRealtime()) { timeout ->
                        val lock = wakeLock
                        wakeControl.execute { lock?.acquire(timeout) }
                    }
                    if (app.engine.state.active) app.tick()
                    val nowMs = SystemClock.elapsedRealtime()
                    val utcMs = System.currentTimeMillis()
                    app.hub.updateGpsState(nowMs)
                    app.mockLocationSharing.update(app.engine.state)
                    app.positionSharing.update(
                        app.engine.state,
                        nowMs,
                        utcMs,
                        app.powerProfile.value.positionSharingMs,
                        NavigationMirrorInput(app.engine.state, app.hub.gpsState, app.hub.lastJudged?.verdict?.level, app.hub.jammed),
                        if (app.engine.state.active) null else NavigationPositionSample.fromIdle(nowMs, utcMs, app.hub.lastGood, app.hub.lastNet),
                    )
                    if (tickCount % NOTIFY_EVERY_TICKS == 0) updateNotification()
                    tickCount++
                    delay(cadence.delayAfterWork(SystemClock.elapsedRealtime()).milliseconds)
                }
                stopSelf()
            }
        }
        // START_STICKY: if Android kills the process, it restarts the service later; the app then
        // restores the saved trip (see TripManager.restore).
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        graph.mockLocationSharing.stop()
        graph.positionSharing.stop()
        graph.onNavigationServiceStopped()
        graph.stopSensing()
        val lock = wakeLock
        wakeControl.execute { if (lock?.isHeld == true) lock.release() }
        wakeControl.shutdown()
        super.onDestroy()
    }

    /** Show the next maneuver (e.g. "400 m · Turn left onto Khreshchatyk") in the notification. */
    private fun updateNotification() {
        val state = graph.engine.state
        val step = state.nextStep
        val guidance = when {
            !state.active -> getString(
                if (graph.positionSharing.status.value.local.phase == PositionSharingPhase.ACTIVE ||
                    graph.positionSharing.status.value.internet.phase == PositionSharingPhase.ACTIVE
                ) {
                    R.string.position_sharing_active
                } else {
                    R.string.position_sharing_waiting
                },
            )

            state.arrived -> getString(R.string.arrived)

            step != null -> formatDistance(resources, state.distToNextM) + " · " + instructionLine(resources, step)

            else -> formatDuration(resources, state.remainingS)
        }
        val text = if (graph.positionSharing.status.value.enabled) {
            getString(R.string.position_sharing_notification, guidance)
        } else if (graph.mockLocationSharing.status.value == MockLocationStatus.ACTIVE) {
            getString(R.string.mock_location_notification, guidance)
        } else {
            guidance
        }
        if (text == lastText) return // posting an unchanged notification still costs IPC and wakes System UI
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    /** The ongoing notification showing [text]. */
    private fun notification(text: String): Notification {
        // Creating an existing channel again is a no-op, so this is safe to call every time.
        // IMPORTANCE_LOW: shown silently, without sound or pop-up.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        // Tapping the notification opens the app.
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(getString(if (graph.engine.state.active) R.string.notification_title else R.string.position_sharing_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "nav"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val NOTIFY_EVERY_TICKS = 4 // = every 2 s
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = MAX_WAKE_LOCK_MS / 2

        /** Start the service (must become a foreground service within seconds). */
        fun start(context: Context) = context.startForegroundService(Intent(context, NavService::class.java))

        /** Ask the service to stop. */
        fun stop(context: Context) = context.stopService(Intent(context, NavService::class.java))
    }
}
