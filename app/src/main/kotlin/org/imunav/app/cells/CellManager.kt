package org.imunav.app.cells

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.app.setup.Preparation
import org.imunav.app.setup.bundledCellPreparation
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.imunav.core.cells.cellLearningKeys
import org.imunav.core.gnss.PositioningHub
import org.imunav.core.net.HttpException
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Owns the offline cell pipeline: scanning, the multi-source tower database, learning from trusted
 * GPS, OpenCellID / Mozilla imports and two-way sync with a cell-sharing server.
 */
class CellManager(private val context: Context, private val scope: CoroutineScope, private val hub: PositioningHub, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("cells", Context.MODE_PRIVATE)
    private val auth = CellAuth(context)
    private val privacy = CellPrivacy(context, auth)

    /** A string resource in the current app language. */
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)
    val db = CellDatabase(context)
    private val transfers = CellTransfers(context, db, prefs, auth, privacy, ::progress)
    private var lastCellLogMs = 0L
    private var lastLearnedFixMs = -1L
    private var task: Job? = null

    private val _status = MutableStateFlow(CellStatus())
    val status: StateFlow<CellStatus> = _status.asStateFlow()

    val usageHistory = CellUsageHistory(context, scope, log)

    val scanner = CellScanner(
        context,
        db,
        onFix = { raw, fix ->
            hub.onFix(raw)
            if (raw.elapsedMs - lastCellLogMs >= 30_000) {
                lastCellLogMs = raw.elapsedMs
                log("cell_fix towers=${fix.towersUsed}/${fix.towersSeen} acc=${fix.accuracyM.toInt()} %.5f %.5f".format(Locale.US, fix.lat, fix.lon))
            }
        },
        log = log,
        onUsage = usageHistory::record,
        onServingMcc = { mcc ->
            if (!prefs.contains("mccs")) {
                prefs.edit { putString("mccs", mcc.toString()) }
                scope.launch { refresh() }
            }
        },
        enabledRadios = ::enabledRadios,
    )

    private val towerDisplay = CellTowerLayerCoordinator(scope, db, { scanner.lastUsable }, ::enabledRadios) { prefs.getBoolean("show_towers", false) }
    val towerLayer: StateFlow<TowerLayer> = towerDisplay.layer

    init {
        if (prefs.contains("sync_key")) prefs.edit { remove("sync_key") }
        scope.launch {
            runCatching {
                reloadCounts()
                installBundledIfNeeded()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _status.update { it.copy(preparation = Preparation.FAILED, message = str(R.string.task_failed, error.message.orEmpty())) }
            }
            withContext(Dispatchers.IO) { runCatching { privacy.flushWithdrawals(diagnosticServerUrl()) } }
            withContext(Dispatchers.IO) { runCatching { privacy.refreshRemote(diagnosticServerUrl()) } }
            if (prefs.getBoolean("auto_sync", false) && System.currentTimeMillis() - prefs.getLong("last_sync_ms", 0) > AUTO_SYNC_INTERVAL_MS) sync(auto = true)
        }
    }

    /** Cell types (LTE, 5G, …) used for positioning; LTE + 5G by default. */
    fun enabledRadios(): Set<Radio> = prefs.getString("radios", null)?.split(',')?.mapNotNull { n -> Radio.entries.firstOrNull { it.name == n } }?.toSet()
        ?: DEFAULT_RADIOS

    /** Turn one cell type on or off (Settings chips). */
    fun setRadioEnabled(radio: Radio, on: Boolean) {
        val set = if (on) enabledRadios() + radio else enabledRadios() - radio
        prefs.edit { putString("radios", set.joinToString(",") { it.name }) }
        log("cell_radios ${set.joinToString(",") { it.name }}")
        refresh()
        towerDisplay.invalidate(refresh = true)
    }

    /** The configured country codes as numbers. */
    private fun mccSet(): Set<Int> = parseMccs(mccText())
    private fun mccText(): String = prefs.getString("mccs", "").orEmpty()

    /** Country-scoped downloads must never silently fall back to another country. */
    private fun requireMccs(): Set<Int> = mccSet().takeIf { it.isNotEmpty() } ?: throw IOException(str(R.string.cells_region_required))

    /** Called ~1/s from the UI refresh loop. */
    fun refresh() {
        maybeLearn()
        val fix = scanner.lastFix
        _status.update {
            it.copy(
                seen = scanner.lastUsable.size,
                radios = enabledRadios(),
                located = fix?.towersUsed ?: 0,
                accuracyM = fix?.accuracyM,
                learning = prefs.getBoolean("learning", true),
                showTowers = prefs.getBoolean("show_towers", false),
                hasToken = prefs.getString("token", "").orEmpty().isNotBlank(),
                mccs = mccText(),
                syncUrl = prefs.getString("sync_url", "").orEmpty(),
                accountEmail = auth.email,
                autoSync = prefs.getBoolean("auto_sync", false),
                lastSync = prefs.getString("last_sync_msg", null),
            )
        }
    }

    /** Re-count towers per source (a database query, so on the IO dispatcher). */
    private suspend fun reloadCounts() {
        val counts = withContext(Dispatchers.IO) { db.counts() }
        towerDisplay.invalidate() // the database changed: the next viewport must re-query
        _status.update { it.copy(counts = counts) }
    }

    // ------------------------------------------------------------------ settings

    /** Random, install-scoped identifier sent to the sharing server (not tied to the phone or user). */
    private fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit { putString("device_id", it) }
    }

    /** Reuse the signed-in cell-server session for opt-in diagnostics, on an I/O thread. */
    fun diagnosticCredentials(expectedUrl: String? = null): Pair<String, String>? {
        val url = prefs.getString("sync_url", "").orEmpty().trim().trimEnd('/')
        if (expectedUrl != null && expectedUrl != url) return null
        if (url.isBlank() || auth.email == null || !privacy.canCaptureDiagnostics(url)) return null
        val token = auth.accessToken(url) ?: return null
        if (!privacy.canSendDiagnostics(url, token)) return null
        return url to token
    }

    /** Reuse the account session for the separately opted-in air-alert stream, on an I/O thread. */
    fun accountCredentials(): Pair<String, String>? {
        val url = diagnosticServerUrl()
        if (url.isBlank() || auth.email == null) return null
        val token = auth.accessToken(url) ?: return null
        return url to token
    }

    /** Current account server, captured when a diagnostic trip starts. */
    fun diagnosticServerUrl(): String = prefs.getString("sync_url", "").orEmpty().trim().trimEnd('/')

    /** Read the saved account directly so a trip can start before the next status refresh. */
    fun diagnosticAccountEmail(): String? = auth.email

    /** Stable identity prevents email changes or a second server from mixing profiles. */
    fun accountId(): Long = auth.accountId

    /** Opaque server identity remains stable across email changes but not account deletion. */
    fun syncIdentity(): String = auth.syncIdentity

    /** Do not capture precise location without a current local diagnostics receipt. */
    fun diagnosticConsentGranted(): Boolean = privacy.canCaptureDiagnostics(diagnosticServerUrl())

    /** Read the selected server's current notice on an I/O thread. */
    suspend fun privacyNotice(url: String): CellPrivacyNotice? = withContext(Dispatchers.IO) { privacy.notice(url) }

    /** Local receipt state used by Settings; the server checks its own receipt on every upload. */
    fun privacyGranted(purpose: String, version: String): Boolean = privacy.isGranted(diagnosticServerUrl(), purpose, version)

    /** Record a separate server receipt only after the person has read that notice. */
    suspend fun grantPrivacyConsent(purpose: String, version: String) = withContext(Dispatchers.IO) {
        privacy.grant(diagnosticServerUrl(), purpose, version)
    }

    /** Stop uploads locally before the queued server deletion is attempted. */
    fun withdrawPrivacyConsent(purpose: String) {
        val url = diagnosticServerUrl()
        privacy.withdrawLocally(url, purpose)
        if (purpose == "tower_upload") prefs.edit { putBoolean("auto_sync", false) }
        scope.launch(Dispatchers.IO) { runCatching { privacy.flushWithdrawals(url) } }
        refresh()
    }

    /** The saved OpenCellID token, for pre-filling Settings fields. */
    fun savedToken(): String = prefs.getString("token", "").orEmpty()

    /** Enable or disable learning tower positions from GPS. */
    fun setLearning(on: Boolean) = prefs.edit { putBoolean("learning", on) }

    /** Show or hide the tower layer on the map. */
    fun setShowTowers(on: Boolean) {
        prefs.edit { putBoolean("show_towers", on) }
        refresh()
        towerDisplay.setVisible(on)
    }

    /** Map camera settled: load towers for the visible area (debounced by cancelling the previous query). */
    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) = towerDisplay.onViewport(south, west, north, east, zoom)

    /** Save the sharing-server and country settings from the Settings screen. */
    fun saveSettings(syncUrl: String, autoSync: Boolean, mccs: String) {
        val serverChanged = prefs.getString("sync_url", "").orEmpty().trimEnd('/') != syncUrl.trim().trimEnd('/')
        val countries = mccs.trim()
        val countriesChanged = prefs.getString("mccs", "").orEmpty() != countries
        if (serverChanged) auth.clear()
        prefs.edit {
            putString("sync_url", syncUrl.trim())
            putBoolean("auto_sync", autoSync)
            if (countries.isBlank()) remove("mccs") else putString("mccs", countries)
            if (serverChanged) {
                putLong("last_upload_ms", 0)
                putLong("last_download_s", 0)
            }
            if (serverChanged || countriesChanged) putBoolean("shared_removal_sync_v1", false)
        }
        refresh()
    }

    /** Authenticate with the selected sharing server without storing the password. */
    fun authenticate(email: String, password: String, register: Boolean) = runTask(str(R.string.task_authenticating)) {
        try {
            withContext(Dispatchers.IO) { auth.authenticate(prefs.getString("sync_url", "").orEmpty(), email, password, register) }
        } catch (error: HttpException) {
            throw IOException(str(authError(error.code)), error)
        }
        prefs.edit { putLong("last_upload_ms", 0) }
        withContext(Dispatchers.IO) { runCatching { privacy.flushWithdrawals(diagnosticServerUrl()) } }
        withContext(Dispatchers.IO) { runCatching { privacy.refreshRemote(diagnosticServerUrl()) } }
        refresh()
        if (auth.emailVerified) str(R.string.auth_signed_in) else str(R.string.auth_verify_email)
    }

    /** Ask the server to send a one-use recovery link. */
    fun requestPasswordReset(email: String) = runTask(str(R.string.task_authenticating)) {
        try {
            withContext(Dispatchers.IO) { auth.requestReset(prefs.getString("sync_url", "").orEmpty(), email) }
        } catch (error: HttpException) {
            throw IOException(str(authError(error.code)), error)
        }
        str(R.string.auth_reset_sent)
    }

    /** Turn expected account errors into localizable UI messages. */
    private fun authError(code: Int): Int = when (code) {
        400 -> R.string.auth_invalid_input
        401 -> R.string.auth_invalid_credentials
        409 -> R.string.auth_email_exists
        503 -> R.string.auth_mail_unavailable
        else -> R.string.auth_server_error
    }

    /** Revoke the active sharing session and clear its local credentials. */
    fun signOut() = runTask(str(R.string.task_authenticating)) {
        try {
            withContext(Dispatchers.IO) { auth.signOut(prefs.getString("sync_url", "").orEmpty()) }
        } finally {
            refresh()
        }
        str(R.string.auth_signed_out)
    }

    // ------------------------------------------------------------------ long-running tasks

    /**
     * Run one long task (import, download, sync…) at a time, showing [start] as progress text.
     * The returned string is shown to the user when it finishes (also after failure or cancel).
     */
    private fun runTask(start: String, cancellable: Boolean = false, block: suspend () -> String) {
        if (task?.isActive == true) return
        task = scope.launch {
            _status.update { it.copy(busy = start, busyCancellable = cancellable, message = null) }
            val msg = try {
                block()
            } catch (_: CancellationException) {
                str(R.string.task_cancelled)
            } catch (_: InterruptedException) {
                str(R.string.task_cancelled)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Task boundary: any failure (I/O, SQLite, parsing) is reported to the user, not crashed on.
                Log.w("CellManager", "task failed", e)
                str(R.string.task_failed, e.message ?: e.javaClass.simpleName)
            }
            log("cells_task ${msg.lowercase()}")
            // After a cancel this coroutine is cancelled: without NonCancellable, reloadCounts() would
            // throw immediately and the busy indicator would never clear.
            withContext(NonCancellable) {
                reloadCounts()
                _status.update { it.copy(busy = null, busyCancellable = false, message = msg) }
            }
        }
    }

    /** Cancel the running task (the Cancel button). */
    fun cancelTask() {
        task?.cancel()
    }

    /** Update the progress text of the running task (any thread). */
    private fun progress(text: String) {
        scope.launch { _status.update { it.copy(busy = text) } }
    }

    /** Import an OpenCellID / Mozilla CSV picked by the user, filtered to the configured MCCs. */
    fun importFile(open: () -> InputStream?) = runTask(str(R.string.task_importing_towers, 0), cancellable = true) {
        str(R.string.task_imported, transfers.importFile(open, requireMccs()))
    }

    /** Download and import the OpenCellID export for the configured MCCs with the user's token. */
    fun downloadOpenCellId(token: String) {
        if (token.isBlank()) return
        prefs.edit { putString("token", token.trim()) }
        runTask(str(R.string.task_downloading_ocid, 0)) {
            str(R.string.task_ocid_done, transfers.downloadOpenCellId(token, requireMccs()))
        }
    }

    /** Stream the Mozilla Location Service final export, retaining only configured MCCs. */
    fun downloadMozilla() = runTask(str(R.string.task_connecting), cancellable = true) {
        str(R.string.task_mozilla_done, transfers.downloadMozilla(requireMccs()))
    }

    /**
     * Import the tower database shipped in the APK (assets/cells/bundled-cells.csv.gz) into the
     * BUNDLED source after explicit opt-in, and again only when an app update ships a different file
     * (detected by SHA-256 of the asset).
     */
    private suspend fun installBundledIfNeeded(force: Boolean = false) {
        val preparation = bundledCellPreparation(
            enabled = prefs.getBoolean("bundled_enabled", false),
            installed = prefs.contains("bundled_sha256") && (_status.value.counts[CellSource.BUNDLED] ?: 0) > 0,
        )
        _status.update { it.copy(preparation = preparation) }
        if (preparation == Preparation.CHECKING) importBundled(force)
    }

    /** Verify the opted-in archive and publish READY only after a complete, cancellable import. */
    private suspend fun importBundled(force: Boolean) {
        // The Android build un-gzips *.gz assets and drops the extension, so accept either name.
        // Finding and hashing the asset reads tens of MB: done on the IO dispatcher, not the main thread.
        _status.update { it.copy(preparation = Preparation.CHECKING) }
        val bundled = runCatching { withContext(Dispatchers.IO) { bundledAssetAndHash() } }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                _status.update { it.copy(preparation = Preparation.FAILED, message = str(R.string.task_failed, error.message.orEmpty())) }
                return
            }
        if (bundled == null) {
            _status.update { it.copy(preparation = Preparation.UNAVAILABLE) }
            return
        }
        val (asset, hash) = bundled
        if (!force && prefs.getString("bundled_sha256", null) == hash && (_status.value.counts[CellSource.BUNDLED] ?: 0) > 0) {
            _status.update { it.copy(preparation = Preparation.READY) }
            return
        }
        if (task?.isActive == true) {
            _status.update { it.copy(preparation = Preparation.FAILED) }
            return
        }
        _status.update { it.copy(preparation = Preparation.PREPARING) }
        runTask(str(R.string.task_bundled_progress, 0)) {
            // Invalidate before clearing: a killed or failed import must never certify partial rows.
            prefs.edit { remove("bundled_sha256") }
            val n = withContext(Dispatchers.IO) {
                db.clear(CellSource.BUNDLED)
                context.assets.open(asset).use { input ->
                    val job = coroutineContext
                    db.importStream(CellSource.BUNDLED, input) { _, kept ->
                        progress(str(R.string.task_bundled_progress, kept))
                        job.isActive
                    }
                }
            }
            coroutineContext.ensureActive()
            if (n == 0L) throw IOException(str(R.string.setup_failed))
            prefs.edit { putString("bundled_sha256", hash) }
            _status.update { it.copy(preparation = Preparation.READY) }
            str(R.string.task_bundled_done, n)
        }
        task?.join()
        if (_status.value.preparation == Preparation.PREPARING) _status.update { it.copy(preparation = Preparation.FAILED) }
    }

    /** Opt in to built-in towers (or retry their import), without overlapping another database task. */
    fun retryBundled() {
        if (task?.isActive == true || _status.value.preparation in setOf(Preparation.CHECKING, Preparation.PREPARING)) return
        prefs.edit { putBoolean("bundled_enabled", true) }
        _status.update { it.copy(preparation = Preparation.CHECKING) }
        scope.launch { installBundledIfNeeded() }
    }

    /** The bundled tower asset's name and SHA-256, or null if the APK has none (IO thread). */
    private fun bundledAssetAndHash(): Pair<String, String>? = transfers.bundledAssetAndHash()

    /**
     * Delete downloaded/imported towers (all sources except, optionally, the ones this phone learned),
     * compact the file, then re-import the database bundled with the APK only if the user opted in.
     */
    fun resetDatabase(deleteLearned: Boolean) {
        runTask(str(R.string.task_clearing)) {
            withContext(Dispatchers.IO) {
                for (s in CellSource.entries) {
                    if (s == CellSource.LEARNED && !deleteLearned) continue
                    progress(str(R.string.task_clearing))
                    db.clear(s)
                }
                progress(str(R.string.task_compacting))
                db.vacuum()
            }
            prefs.edit {
                remove("bundled_sha256")
                putLong("last_download_s", 0) // next sync downloads the full shared dataset again
                putBoolean("shared_removal_sync_v1", false)
            }
            if (deleteLearned) prefs.edit { putLong("last_upload_ms", 0) }
            log("cells_reset learned_deleted=$deleteLearned")
            str(if (deleteLearned) R.string.task_cleared else R.string.task_cleared_kept)
        }
        // Runs after the clearing task finishes (tasks don't overlap).
        scope.launch {
            task?.join()
            installBundledIfNeeded(force = true)
        }
    }

    /** Export all sources, deduplicated, to the app's external files directory. */
    fun exportDatabase() = runTask(str(R.string.task_exporting, 0)) {
        val result = transfers.exportDatabase()
        str(R.string.task_exported, result.count, result.sizeKib.toInt())
    }

    /** Upload learned towers, then download merged data into the SHARED source. */
    fun sync(auto: Boolean = false) {
        val url = prefs.getString("sync_url", "").orEmpty()
        if (url.isBlank()) {
            if (!auto) _status.update { it.copy(message = str(R.string.task_sync_no_url)) }
            return
        }
        if (mccSet().isEmpty()) {
            if (!auto) _status.update { it.copy(message = str(R.string.cells_region_required)) }
            return
        }
        runTask(str(R.string.task_syncing)) {
            val started = System.currentTimeMillis()
            val (uploaded, downloaded) = transfers.sync(url, deviceId(), requireMccs(), started)
            val msg = when {
                auth.email == null -> context.resources.getQuantityString(R.plurals.task_sync_download_only, downloaded, downloaded)
                !auth.emailVerified -> context.resources.getQuantityString(R.plurals.task_sync_unverified, downloaded, downloaded)
                !auth.sharingEnabled -> context.resources.getQuantityString(R.plurals.task_sync_paused, downloaded, downloaded)
                else -> str(R.string.task_sync_done, uploaded, downloaded)
            }
            prefs.edit {
                putLong("last_sync_ms", started)
                putString("last_sync_msg", "$msg (${DateFormat.getDateTimeInstance().format(Date(started))})")
            }
            msg
        }
    }

    /** Opportunistic sync (e.g. after a trip) when auto-sync is on and the last one is old. */
    fun maybeAutoSync() {
        if (prefs.getBoolean("auto_sync", false) && System.currentTimeMillis() - prefs.getLong("last_sync_ms", 0) > AUTO_SYNC_INTERVAL_MS) sync(auto = true)
    }

    /** Record where visible cells are whenever we have a fresh, accurate GOOD GPS fix. */
    private fun maybeLearn() {
        if (!prefs.getBoolean("learning", true)) return
        val good = hub.lastGood ?: return
        if (good.elapsedMs == lastLearnedFixMs) return
        val acc = good.accuracyM ?: return
        val keys = cellLearningKeys(scanner.lastMeasurements, good.elapsedMs, SystemClock.elapsedRealtime())
        if (acc > 30f || keys.isEmpty()) return
        if (lastLearnedFixMs > 0 && good.elapsedMs - lastLearnedFixMs < 20_000) return
        lastLearnedFixMs = good.elapsedMs
        scope.launch {
            withContext(Dispatchers.IO) { db.learn(keys, good.lat, good.lon, acc.toDouble()) }
            reloadCounts()
        }
    }

    companion object {
        /** LTE and 5G cells are small and well located; 2G/3G cells are large and give coarse fixes. */
        val DEFAULT_RADIOS: Set<Radio> = setOf(Radio.LTE, Radio.NR)
        const val MIN_TOWER_ZOOM = 11.0
        const val MAX_TOWERS_ON_MAP = 4000
        const val MOZILLA_URL = "https://archive.org/download/MLS_Full_Cell_Export_Final/MLS-full-cell-export-final.csv.gz"
        private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

/** Parse the user's comma/space-separated MCCs without accepting a partial selection. */
internal fun parseMccs(text: String): Set<Int> {
    val values = text.split(',', ' ', '\n').filter(String::isNotBlank)
    if (values.isEmpty()) return emptySet()
    val parsed = values.map { it.toIntOrNull()?.takeIf { mcc -> mcc in 1..999 } ?: return emptySet() }
    return parsed.toSet()
}
