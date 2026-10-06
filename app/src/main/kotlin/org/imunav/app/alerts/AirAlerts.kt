package org.imunav.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.imunav.app.AppLanguage
import org.imunav.app.R
import org.imunav.app.cells.CellManager
import org.imunav.app.ui.MainActivity
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.json.JSONObject
import java.io.IOException

/** One official oblast in the latest UkraineAlarm snapshot. */
data class AirAlertRegion(val id: String, val nameUk: String, val nameEn: String)

/** A snapshot does not trigger notifications; only changes received while visible do. */
data class AirAlertStatus(
    val signedIn: Boolean = false,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val active: List<AirAlertRegion> = emptyList(),
    val stale: Boolean = true,
    val notificationsAllowed: Boolean = true,
    val error: Boolean = false,
)

/**
 * Maintains the user WebSocket only while the phone UI is visible. The server owns polling and
 * webhook reconciliation; Android never contacts UkraineAlarm or sends location for this feature.
 */
class AirAlerts(private val context: Context, private val scope: CoroutineScope, private val cells: CellManager) {
    private val _status = MutableStateFlow(AirAlertStatus())
    val status: StateFlow<AirAlertStatus> = _status.asStateFlow()

    @Volatile private var visible = false

    @Volatile private var generation = 0L
    private var monitor: Job? = null

    /** Activity visibility is the only reason to maintain a live socket. */
    fun setVisible(on: Boolean) {
        if (visible == on) return
        visible = on
        refresh()
    }

    /** Reload the server preference after account changes or a settings action. */
    fun refresh() {
        generation++
        monitor?.cancel()
        monitor = if (visible) scope.launch { monitorVisible() } else null
        if (!visible) _status.update { it.copy(connected = false, active = emptyList(), stale = true) }
    }

    /** Change the server-side account preference; the local socket follows only after success. */
    suspend fun setEnabled(enabled: Boolean) {
        val (url, token) = withContext(Dispatchers.IO) { cells.accountCredentials() } ?: throw IOException(context.getString(R.string.air_alerts_sign_in))
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("enabled", enabled).toString().toRequestBody("application/json".toMediaType())
            Http.client.newCall(Request.Builder().url(url + PREFERENCE_PATH).header("Authorization", "Bearer $token").put(body).build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(response.code, response.body.string().take(Http.ERROR_BODY_CHARS))
            }
        }
        _status.update { it.copy(enabled = enabled) }
        refresh()
    }

    private suspend fun monitorVisible() {
        while (visible && kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                val credentials = withContext(Dispatchers.IO) { cells.accountCredentials() }
                if (credentials == null) {
                    val allowed = withContext(Dispatchers.IO) { notificationsAllowed() }
                    _status.value = AirAlertStatus(notificationsAllowed = allowed)
                    delay(RETRY_MS)
                    continue
                }
                val (url, token) = credentials
                val preference = withContext(Dispatchers.IO) { fetchPreference(url, token) }
                val allowed = withContext(Dispatchers.IO) { notificationsAllowed() }
                _status.update {
                    it.copy(signedIn = true, enabled = preference.first, available = preference.second, notificationsAllowed = allowed, error = false)
                }
                if (preference.first && preference.second) {
                    receive(url, token)
                    delay(RETRY_MS)
                } else {
                    _status.update { it.copy(connected = false, active = emptyList()) }
                    delay(RETRY_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _status.update { it.copy(connected = false, stale = true, error = true) }
                delay(RETRY_MS)
            }
        }
    }

    private fun fetchPreference(url: String, token: String): Pair<Boolean, Boolean> {
        Http.client.newCall(Request.Builder().url(url + PREFERENCE_PATH).header("Authorization", "Bearer $token").build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, response.body.string().take(Http.ERROR_BODY_CHARS))
            val value = JSONObject(response.body.string())
            return value.getBoolean("enabled") to value.getBoolean("available")
        }
    }

    private suspend fun receive(url: String, token: String) {
        val session = generation
        val closed = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url + STREAM_PATH).header("Authorization", "Bearer $token").build()
        val socket = Http.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { if (visible && generation == session) _status.update { it.copy(connected = true, error = false) } }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length <= MAX_EVENT_CHARS) scope.launch { if (visible && generation == session) handleMessage(text, session) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    closed.complete(Unit)
                }
            },
        )
        try {
            closed.await()
        } finally {
            socket.cancel()
            if (generation == session) _status.update { it.copy(connected = false, stale = true) }
        }
    }

    private fun handleMessage(text: String, session: Long) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("type")) {
            "snapshot" -> {
                val active = regions(message, "active")
                _status.update { it.copy(active = active, stale = message.optBoolean("stale", true)) }
            }

            "changes" -> {
                val started = regions(message, "started")
                val cleared = regions(message, "cleared")
                val active = _status.value.active.associateBy { it.id }.toMutableMap()
                cleared.forEach { active.remove(it.id) }
                started.forEach { active[it.id] = it }
                _status.update { it.copy(active = active.values.toList(), stale = false) }
                if (_status.value.enabled && visible && (started.isNotEmpty() || cleared.isNotEmpty())) {
                    scope.launch(Dispatchers.IO) { runCatching { notifyChange(started, cleared, session) } }
                }
            }
        }
    }

    private fun regions(message: JSONObject, key: String): List<AirAlertRegion> {
        val array = message.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                val id = value.optString("region_id")
                if (id.isBlank()) continue
                add(AirAlertRegion(id, value.optString("name_uk", id), value.optString("name_en", id)))
            }
        }
    }

    private fun notificationsAllowed(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE && (
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
    }

    private fun notifyChange(started: List<AirAlertRegion>, cleared: List<AirAlertRegion>, session: Long) {
        if (!visible || generation != session || !_status.value.enabled || cells.diagnosticAccountEmail() == null || !notificationsAllowed()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.air_alerts_channel), NotificationManager.IMPORTANCE_HIGH))
        val title = context.getString(
            when {
                started.isNotEmpty() && cleared.isNotEmpty() -> R.string.air_alerts_notification_update
                started.isNotEmpty() -> R.string.air_alerts_notification_start
                else -> R.string.air_alerts_notification_clear
            },
        )
        val regions = (started + cleared).take(MAX_NAMES)
        val language = AppLanguage.locale(AppLanguage.get(context)).language
        val names = regions.joinToString(", ") { if (language == "en") it.nameEn else it.nameUk }
        val truncated = started.size + cleared.size > regions.size
        val body = if (truncated) context.getString(R.string.air_alerts_notification_truncated, names) else names
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (visible && generation == session && _status.value.enabled) manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val PREFERENCE_PATH = "/v1/air-alerts/preferences"
        const val STREAM_PATH = "/v1/air-alerts/stream"
        const val CHANNEL = "air_alerts"
        const val NOTIFICATION_ID = 81
        const val MAX_NAMES = 3
        const val MAX_EVENT_CHARS = 32_768
        const val RETRY_MS = 30_000L
    }
}
