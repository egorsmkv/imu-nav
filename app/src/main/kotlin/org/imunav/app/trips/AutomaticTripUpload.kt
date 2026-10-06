package org.imunav.app.trips

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.sync.SyncDisk

/** Local opt-in is independent for each server/account and never grants server consent. */
data class AutomaticTripUploadStatus(val available: Boolean = false, val enabled: Boolean = false, val busy: Boolean = false, val failed: Boolean = false)

/** Persists only explicitly queued completed trips. Retries on app resume and account refresh, without polling. */
class AutomaticTripUpload(context: Context, private val app: AppGraph) {
    private val preferences = context.getSharedPreferences("automatic_trip_upload", Context.MODE_PRIVATE)
    private val disk = Mutex()
    private val _status = MutableStateFlow(AutomaticTripUploadStatus())
    val status = _status.asStateFlow()
    private var worker: Job? = null
    private var noticeVersion = ""
    private var identity = ""

    init {
        app.scope.launch {
            app.cells.status.map { profile() }.distinctUntilChanged().collect {
                identity = it
                noticeVersion = ""
                worker?.cancel()
                _status.value = AutomaticTripUploadStatus()
                refresh()
            }
        }
        app.scope.launch { app.trips.history.collect { if (it.isNotEmpty()) refresh() } }
    }

    private fun profile(): String = if (app.cells.accountId() > 0 && app.cells.syncIdentity().isNotBlank()) {
        SyncDisk.profile(app.cells.diagnosticServerUrl(), app.cells.accountId(), app.cells.syncIdentity())
    } else {
        ""
    }

    /** Capture ownership at the start, so changing accounts mid-trip cannot send it to another owner. */
    fun started(id: String) {
        val owner = profile()
        app.scope.launch {
            disk.withLock {
                withContext(Dispatchers.IO) {
                    if (!preferences.contains("owner:$id")) preferences.edit(commit = true) { putString("owner:$id", owner) }
                }
            }
        }
    }

    /** Called after the recording and history entry have been flushed on TripManager's executor. */
    fun completed(id: String) {
        app.scope.launch {
            val owner = profile()
            disk.withLock {
                withContext(Dispatchers.IO) {
                    if (app.trips.history.value.any { it.id == id } && owner.isNotEmpty() && preferences.getString("owner:$id", "") == owner &&
                        preferences.contains("enabled:$owner")
                    ) {
                        val pending = preferences.getStringSet("pending:$owner", emptySet()).orEmpty() + id
                        preferences.edit(commit = true) {
                            putStringSet("pending:$owner", pending)
                            remove("owner:$id")
                        }
                    } else {
                        preferences.edit(commit = true) { remove("owner:$id") }
                    }
                }
            }
            refresh()
        }
    }

    /** Disable also drops queued uploads; checking the box covers future completed trips only. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && (!_status.value.available || noticeVersion.isBlank())) return
        val owner = identity
        if (owner.isEmpty() || owner != profile()) return
        val version = noticeVersion
        worker?.cancel()
        _status.value = _status.value.copy(enabled = enabled, busy = false)
        app.scope.launch {
            disk.withLock {
                withContext(Dispatchers.IO) {
                    preferences.edit(commit = true) {
                        if (enabled) putString("enabled:$owner", version) else remove("enabled:$owner")
                        remove("pending:$owner")
                    }
                }
            }
            refresh()
        }
    }

    /** Rechecks the server before every batch; unavailable servers never receive automatic uploads. */
    // Background uploads report network, parsing and local recording failures through Settings.
    @Suppress("TooGenericExceptionCaught")
    fun refresh() {
        worker?.cancel()
        val owner = profile()
        if (owner.isEmpty()) return
        worker = app.scope.launch {
            try {
                val accepted = disk.withLock { withContext(Dispatchers.IO) { preferences.getString("enabled:$owner", null) } }
                _status.value = _status.value.copy(enabled = accepted != null, busy = true, failed = false)
                val credentials = withContext(Dispatchers.IO) { app.cells.accountCredentials() } ?: return@launch
                if (profile() != owner) return@launch
                val client = TripArchiveClient(credentials.first, credentials.second)
                val metadata = client.metadata()
                if (profile() != owner) return@launch
                noticeVersion = metadata.getString("notice_version")
                val available = metadata.getBoolean("enabled")
                val enabled = accepted != null && available && accepted == noticeVersion
                _status.value = AutomaticTripUploadStatus(available, enabled, busy = true)
                if (accepted != null && !enabled) {
                    setEnabled(false)
                    return@launch
                }
                if (enabled) uploadPending(owner, client, metadata.getJSONObject("limits").getInt("upload_bytes"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (profile() == owner) _status.value = _status.value.copy(available = false, failed = true)
            } finally {
                if (profile() == owner) _status.value = _status.value.copy(busy = false)
            }
        }
    }

    private suspend fun uploadPending(owner: String, client: TripArchiveClient, maxBytes: Int) {
        val pending = withContext(Dispatchers.IO) { preferences.getStringSet("pending:$owner", emptySet()).orEmpty().toSet() }
        for (id in pending) {
            if (profile() != owner) return
            val trip = app.trips.history.value.firstOrNull { it.id == id } ?: continue
            val document = playbackDocument(app.trips.recordingFile(trip), trip)
            if (profile() != owner) return
            client.upload(id, document, maxBytes)
            disk.withLock {
                withContext(Dispatchers.IO) {
                    preferences.edit(commit = true) { putStringSet("pending:$owner", preferences.getStringSet("pending:$owner", emptySet()).orEmpty() - id) }
                }
            }
        }
    }
}
