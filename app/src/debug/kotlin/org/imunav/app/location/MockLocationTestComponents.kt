package org.imunav.app.location

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.imunav.app.graph
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource

/** Empty foreground host avoids map downloads and onboarding in service lifecycle tests. */
class MockLocationTestActivity : Activity() {
    override fun onStart() {
        super.onStart()
        graph.setPhoneVisible(true)
    }

    override fun onStop() {
        graph.setPhoneVisible(false)
        super.onStop()
    }
}

/** Redirect only the GPS worker's preferences; the regular AppGraph cannot clean up this fixture. */
fun mockLocationTestContext(context: Context, preferencesName: String): Context = object : ContextWrapper(context) {
    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(if (name == "mock_location") preferencesName else name, mode)
}

/**
 * Debug-only bound process exercising the production worker with durable preferences and real Binder
 * calls. Killing this process leaves instrumentation alive to observe death and subsequent cleanup.
 */
class MockLocationTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sharing: MockLocationSharing
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                ENABLE -> sharing.setEnabled(message.arg1 != 0)
                PUBLISH -> sharing.update(GuidanceState(active = true, position = GeoPoint(48.125, 28.25), source = PositionSource.DR, uncertaintyM = 75.0))
                STOP -> sharing.stop()
                KILL -> Process.killProcess(Process.myPid())
                SNAPSHOT -> Unit
                else -> error("Unknown GPS test command")
            }
            val prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            message.replyTo?.send(
                Message.obtain(null, message.what).apply {
                    data = Bundle().apply {
                        putInt("pid", Process.myPid())
                        putBoolean("enabled", sharing.enabled.value)
                        putBoolean("installed", prefs.getBoolean(AndroidMockLocationProvider.INSTALLED, false))
                        putString("status", sharing.status.value.name)
                    }
                },
            )
            true
        },
    )

    override fun onCreate() {
        super.onCreate()
        sharing = MockLocationSharing(mockLocationTestContext(this, PREFERENCES), scope)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        // The test client awaits STOP/OFF before unbinding. A kill intentionally bypasses teardown.
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PREFERENCES = "mock_location_process_test"
        const val ENABLE = 1
        const val PUBLISH = 2
        const val STOP = 3
        const val KILL = 4
        const val SNAPSHOT = 5
    }
}
