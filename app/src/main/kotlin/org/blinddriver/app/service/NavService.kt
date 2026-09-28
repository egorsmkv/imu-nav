package org.blinddriver.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.blinddriver.app.R
import org.blinddriver.app.graph
import org.blinddriver.app.ui.MainActivity
import org.blinddriver.app.ui.formatDistance
import org.blinddriver.app.ui.formatDuration
import org.blinddriver.app.ui.instructionLine
import org.blinddriver.core.nav.NavigationEngine

/**
 * Keeps sensors alive with the screen off and drives the engine at a fixed 2 Hz.
 * Started when navigation starts, stopped when it ends.
 */
class NavService : LifecycleService() {
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blinddriver:nav")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        val g = graph
        g.startSensing()
        if (loop == null) {
            loop = lifecycleScope.launch {
                var n = 0
                while (isActive) {
                    g.tick()
                    if (!g.engine.state.active) break
                    if (n++ % 4 == 0) updateNotification()
                    delay(NavigationEngine.TICK_MS)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private var lastText: String? = null

    private fun updateNotification() {
        val st = graph.engine.state
        val step = st.nextStep
        val text = when {
            st.arrived -> getString(R.string.arrived)
            step != null -> formatDistance(resources, st.distToNextM) + " · " + instructionLine(resources, step)
            else -> formatDuration(resources, st.remainingS)
        }
        if (text == lastText) return // posting an unchanged notification still costs IPC and wakes System UI
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(getString(R.string.notification_title))
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

        fun start(context: Context) = context.startForegroundService(Intent(context, NavService::class.java))

        fun stop(context: Context) = context.startService(Intent(context, NavService::class.java).setAction(ACTION_STOP))
    }
}
