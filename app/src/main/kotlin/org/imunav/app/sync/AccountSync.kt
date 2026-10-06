package org.imunav.app.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.bookmarks.BookmarkDatabase
import org.imunav.core.net.HttpException
import org.imunav.core.sync.SyncMerge
import org.json.JSONArray
import org.json.JSONObject

/** UI status contains no server errors, tokens, bookmark coordinates or other private payloads. */
data class AccountSyncStatus(
    val signedIn: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val prompt: Boolean = false,
    val unavailable: Boolean = false,
    val failed: Boolean = false,
    val conflict: Boolean = false,
    val lastSuccess: Long = 0,
    val notice: String = "",
)

/** Serializes profile switching, durable acknowledgement and opt-in transfers on the app scope. */
class AccountSync(private val context: Context, private val app: AppGraph) {
    private val disk = SyncDisk(context)
    private val settings = PortableSettings(app)
    private val _status = MutableStateFlow(AccountSyncStatus())
    val status = _status.asStateFlow()
    private val events = Channel<Unit>(Channel.CONFLATED)
    private var journal = JSONObject()
    private var active = "local"
    private var applying = false
    private var visible = false
    private var decision: Boolean? = null
    private var invite = false
    private var network: Job? = null
    private var identity = ""
    private var initialized = false
    private var recovering = true
    private var retry = 0
    private val preferences = listOf("language", "voice", "haptics", "travel", "power", "map_start", "routing", "offline_map", "cells").map {
        context.getSharedPreferences(it, Context.MODE_PRIVATE)
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> if (!applying) wake() }

    init {
        preferences.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        app.scope.launch {
            app.cells.status.map {
                "${app.cells.diagnosticServerUrl()}:${app.cells.diagnosticAccountEmail()}:${app.cells.accountId()}:${app.cells.syncIdentity()}"
            }.distinctUntilChanged().collect {
                if (identity != it) {
                    identity = it
                    decision = null
                    invite = false
                    _status.value = AccountSyncStatus()
                    network?.cancel()
                    wake()
                }
            }
        }
        app.scope.launch {
            app.bookmarks.state.map { it.items }.distinctUntilChanged().collect { if (!applying) wake() }
        }
        app.scope.launch {
            app.ui.map { it.guidance.active || it.planning || it.startingNavigation }.distinctUntilChanged().collect { if (!it) wake() }
        }
        app.scope.launch {
            try {
                journal = withContext(Dispatchers.IO) { disk.read() }
                initialized = true
                for (event in events) {
                    delay(DEBOUNCE_MS)
                    events.tryReceive()
                    runCycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _status.value = _status.value.copy(failed = true, busy = false)
            }
        }
        wake()
    }

    /** Foreground transitions trigger reconciliation without a permanent polling loop. */
    fun setVisible(value: Boolean) {
        visible = value
        if (value) wake() else network?.cancel()
    }

    fun syncNow() = wake()

    /** Explicit re-enablement refreshes the notice before offering consent. */
    fun requestEnable() {
        invite = true
        wake()
    }

    /** Disable takes effect before any network operation and queues erasure for offline devices. */
    fun choose(enabled: Boolean) {
        decision = enabled
        if (!enabled) network?.cancel()
        _status.value = _status.value.copy(prompt = false, enabled = enabled)
        wake()
    }

    /** A declined invitation stays declined on this device until explicitly enabled in Settings. */
    fun decline() {
        decision = false
        _status.value = _status.value.copy(prompt = false)
        wake()
    }

    private fun wake() {
        events.trySend(Unit)
    }
    private fun profile(key: String): JSONObject = journal.optJSONObject(key) ?: JSONObject().also { journal.put(key, it) }
    private fun safeToRestore(): Boolean = !app.ui.value.guidance.active && !app.ui.value.planning && !app.ui.value.startingNavigation
    private fun currentIdentity(): String = "${app.cells.diagnosticServerUrl()}:${app.cells.diagnosticAccountEmail()}:${app.cells.accountId()}:${app.cells.syncIdentity()}"

    private suspend fun persist() = withContext(Dispatchers.IO + NonCancellable) { disk.write(journal) }
    private suspend fun localValues(): Map<String, String> {
        val choices = settings.capture()
        val bookmarks = app.bookmarks.state.value.items
        return withContext(Dispatchers.Default) { choices + bookmarks.associate { "bookmark:${it.id}" to SyncCodec.bookmark(it) } }
    }

    // Network, JSON and storage failures share one UI error boundary; cancellation is rethrown.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runCycle() {
        if (!initialized) return
        try {
            app.bookmarks.state.first { !it.busy && it.loaded }
            val expected = currentIdentity()
            val data = prepareProfile()
            if (currentIdentity() != expected) {
                wake()
                return
            }
            if (!visible || app.cells.diagnosticAccountEmail() == null) return
            network = app.scope.launch {
                try {
                    _status.value = _status.value.copy(busy = true, failed = false)
                    val credentials = withContext(Dispatchers.IO) { app.cells.accountCredentials() } ?: return@launch
                    if (currentIdentity() != expected) {
                        wake()
                        return@launch
                    }
                    val api = SyncApi(credentials.first, credentials.second)
                    exchange(api, data, expected)
                    retry = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _status.value = _status.value.copy(failed = true, unavailable = error is HttpException && error.code == 404)
                    if (error !is HttpException || error.code >= 500) {
                        if (retry < MAX_RETRIES) {
                            delay(RETRY_MS * (1L shl retry++))
                            wake()
                        }
                    }
                } finally {
                    _status.value = _status.value.copy(busy = false)
                }
            }
            network?.join()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _status.value = _status.value.copy(failed = true, busy = false)
        }
    }

    private suspend fun prepareProfile(): JSONObject {
        val signedIn = app.cells.diagnosticAccountEmail() != null
        if (signedIn && (app.cells.accountId() == 0L || app.cells.syncIdentity().isBlank())) withContext(Dispatchers.IO) { app.cells.accountCredentials() }
        val key = if (signedIn && app.cells.accountId() > 0) SyncDisk.profile(app.cells.diagnosticServerUrl(), app.cells.accountId(), app.cells.syncIdentity()) else "local"
        val data = profile(key)
        if (invite && key != "local") {
            data.put("asked", false)
            invite = false
        }
        if (decision != null && key != "local") {
            val enabled = decision == true
            if (!enabled && (data.optBoolean("enabled") || data.optBoolean("accepted"))) data.put("withdraw", true)
            data.put("asked", true).put("enabled", enabled)
            decision = null
        }
        val next = if (key != "local" && (data.optBoolean("enabled") || data.optBoolean("created"))) key else "local"
        val previous = journal.optString("owner", "local")
        if (!journal.has(previous)) profile(previous).put("settings", JSONObject(settings.capture()))
        if (recovering || active == previous) profile(previous).put("settings", JSONObject(settings.capture()))
        recovering = false
        if (active != next || previous != next) {
            selectProfile(next)
        }
        if (journal.optString("owner", "local") == active) profile(active).put("settings", JSONObject(settings.capture()))
        _status.value = _status.value.copy(signedIn = signedIn, enabled = data.optBoolean("enabled"), lastSuccess = data.optLong("last_success"))
        persist()
        return data
    }

    private suspend fun selectProfile(next: String) {
        applying = true
        try {
            val target = profile(next)
            val savedSettings = target.optJSONObject("settings") ?: profile("local").optJSONObject("settings") ?: JSONObject(settings.capture())
            val seed = if (next != "local" && !target.optBoolean("created") &&
                withContext(Dispatchers.IO) { !context.getDatabasePath("bookmarks-$next.db").exists() }
            ) {
                withContext(Dispatchers.IO) { BookmarkDatabase(context).use { it.load() } }
            } else {
                null
            }
            if (active != next) app.bookmarks.selectStore(BookmarkDatabase(context, if (next == "local") "bookmarks.db" else "bookmarks-$next.db"), seed)
            if (safeToRestore()) {
                settings.validate(SyncDisk.values(savedSettings))
                settings.apply(SyncDisk.values(savedSettings))
                journal.put("owner", next)
            }
            target.put("created", true).put("settings", savedSettings)
            active = next
            persist()
        } finally {
            applying = false
        }
    }

    private suspend fun exchange(api: SyncApi, data: JSONObject, expected: String) {
        var remote = prepareRemote(api, data, expected) ?: return
        if (!safeToRestore()) return
        val sent = localValues()
        val baseline = withContext(Dispatchers.Default) { SyncCodec.entries(data.optJSONArray("baseline") ?: JSONArray()) }
        val changes = withContext(Dispatchers.Default) { SyncMerge.changes(sent, baseline, SyncCodec.entries(remote.getJSONArray("entries")), !data.optBoolean("restored")) }
        for (batch in changes.chunked(BATCH_SIZE)) {
            if (currentIdentity() != expected || decision == false) return
            val body = withContext(Dispatchers.Default) { JSONObject().put("version", 1).put("generation", data.getLong("generation")).put("changes", SyncCodec.entries(batch)) }
            remote = api.request(method = "PUT", body = body)
            if (remote.getJSONArray("conflicts").length() > 0) _status.value = _status.value.copy(conflict = true)
        }
        if (currentIdentity() != expected || decision == false || !safeToRestore()) return
        app.bookmarks.state.first { !it.busy && it.loaded }
        val entries = withContext(Dispatchers.Default) { SyncCodec.entries(remote.getJSONArray("entries")) }
        val values = SyncMerge.apply(entries, sent, localValues())
        settings.validate(values)
        val bookmarks =
            withContext(Dispatchers.Default) { values.filterKeys { it.startsWith("bookmark:") }.map { (key, value) -> SyncCodec.bookmark(key.substringAfter(':'), value) } }
        applying = true
        try {
            app.bookmarks.replaceAll(bookmarks)
            settings.apply(SyncMerge.apply(entries, sent, localValues()))
            val acknowledged = withContext(Dispatchers.Default) { SyncCodec.entries(entries) }
            data.put("settings", JSONObject(settings.capture())).put("baseline", acknowledged).put("restored", true).put("last_success", System.currentTimeMillis())
            persist()
            _status.value = _status.value.copy(enabled = true, unavailable = false, lastSuccess = data.getLong("last_success"))
        } finally {
            applying = false
        }
    }

    private suspend fun prepareRemote(api: SyncApi, data: JSONObject, expected: String): JSONObject? {
        if (data.optBoolean("withdraw")) {
            api.request("/v1/privacy/consents/account_sync", "DELETE")
            data.put("withdraw", false).put("accepted", false).put("baseline", JSONArray()).put("restored", false)
            persist()
        }
        var remote = api.request()
        require(remote.getInt("version") == 1 && remote.getLong("account_id") == app.cells.accountId())
        if (currentIdentity() != expected || (!data.optBoolean("enabled") && data.optBoolean("asked"))) return null
        val version = remote.getString("notice_version")
        val withdrawn = data.optBoolean("accepted") &&
            (data.optString("notice_version") != version || !remote.getBoolean("enabled") || data.optLong("generation") != remote.getLong("generation"))
        val unseen = !data.optBoolean("accepted") && data.optString("offered_version") != version
        if (!data.optBoolean("asked") || withdrawn || unseen) {
            if (withdrawn) data.put("accepted", false).put("restored", false).put("baseline", JSONArray())
            data.put("enabled", false)
            loadNotice(api, version)
            data.put("offered_version", version)
            _status.value = _status.value.copy(enabled = false, prompt = true, unavailable = false)
            persist()
            return null
        }
        if (!data.optBoolean("accepted")) {
            api.request("/v1/privacy/consents/account_sync", "PUT", JSONObject().put("notice_version", version))
            remote = api.request()
            data.put("accepted", true).put("notice_version", version).put("generation", remote.getLong("generation"))
            persist()
        }
        return remote
    }

    private suspend fun loadNotice(api: SyncApi, version: String) {
        val notice = try {
            api.request("/v1/privacy")
        } catch (error: HttpException) {
            if (error.code == 404) JSONObject() else throw error
        }
        require(notice.optString("version", "local") == version) { "Privacy notice changed" }
        _status.value = _status.value.copy(notice = notice.optString("notice_${app.language.value}", notice.optString("notice_en")))
    }

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val RETRY_MS = 2_000L
        const val MAX_RETRIES = 4
        const val BATCH_SIZE = 128
    }
}
