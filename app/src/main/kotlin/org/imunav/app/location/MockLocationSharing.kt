package org.imunav.app.location

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.imunav.core.location.MockLocationSample
import org.imunav.core.location.MockLocationSession
import org.imunav.core.location.MockLocationStatus
import org.imunav.core.nav.GuidanceState

/**
 * Process-owned worker: service teardown can request cleanup after its own lifecycle is cancelled.
 * Conflation bounds queued work; GPS Binder calls and durable ownership writes stay off the UI thread.
 */
class MockLocationSharing(context: Context, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("mock_location", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    private val _status = MutableStateFlow(MockLocationStatus.OFF)
    val status = _status.asStateFlow()
    private val request = MutableStateFlow(Request(_enabled.value, null))

    init {
        scope.launch(Dispatchers.IO) {
            val session = MockLocationSession(AndroidMockLocationProvider(context, prefs), prefs.getBoolean(AndroidMockLocationProvider.INSTALLED, false))
            // Clean up a provider left behind by process death before accepting a new trip.
            _status.value = session.update(false, null)
            request.collect { current ->
                val sample = current.sample?.takeIf { SystemClock.elapsedRealtime() - it.elapsedMs in 0..MAX_SAMPLE_AGE_MS }
                _status.value = session.update(current.enabled, sample)
            }
        }
    }

    /** Device-local opt-in; intentionally excluded from account synchronization. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("enabled", enabled) }
        _enabled.value = enabled
        request.value = request.value.copy(enabled = enabled)
        refresh()
    }

    /** Recheck Android setup when settings resume, including when navigation is idle. */
    fun refresh() {
        request.value = request.value.copy(revision = request.value.revision + 1)
    }

    /** Called only by the foreground service after a fresh navigation tick. */
    fun update(state: GuidanceState) {
        request.value = request.value.copy(sample = MockLocationSample.from(state, SystemClock.elapsedRealtime()))
    }

    /** Remove the provider on navigation stop or service teardown, retaining the user's preference. */
    fun stop() {
        request.value = request.value.copy(sample = null, revision = request.value.revision + 1)
    }

    private data class Request(val enabled: Boolean, val sample: MockLocationSample?, val revision: Long = 0)

    companion object {
        private const val MAX_SAMPLE_AGE_MS = 2_000L
    }
}
