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
import org.imunav.core.cells.CellSyncClient
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.imunav.core.cells.ResumableHttpInputStream
import org.imunav.core.cells.cellLearningKeys
import org.imunav.core.gnss.PositioningHub
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Towers to draw on the map for the current viewport. */
data class TowerLayer(
    val towers: List<CellTower> = emptyList(),
    /** Towers of the cells the phone sees right now (exact or site match). */
    val visible: List<CellTower> = emptyList(),
    /** True when there were more towers than we draw (only a sample is shown). */
    val truncated: Boolean = false,
    /** Map is zoomed out too far to show towers. */
    val zoomTooLow: Boolean = false,
)

/** Offline cell positioning status for the UI. */
data class CellStatus(
    val preparation: Preparation = Preparation.CHECKING,
    /** Cells of enabled types the modem sees now. */
    val seen: Int = 0,
    /** How many of them are in the database (used for the position). */
    val located: Int = 0,
    /** Accuracy of the current cell fix, metres. */
    val accuracyM: Double? = null,
    /** Towers per source in the database. */
    val counts: Map<CellSource, Long> = emptyMap(),
    /** Learn tower positions from trusted GPS. */
    val learning: Boolean = true,
    val showTowers: Boolean = false,
    /** Cell types used for positioning and drawn on the map. */
    val radios: Set<Radio> = CellManager.DEFAULT_RADIOS,
    /** An OpenCellID token has been entered. */
    val hasToken: Boolean = false,
    /** Country codes to import, comma-separated (255 = Ukraine). */
    val mccs: String = "255",
    /** Cell-sharing server settings. */
    val syncUrl: String = "",
    val hasSyncKey: Boolean = false,
    val autoSync: Boolean = false,
    val lastSync: String? = null,
    /** Long-running task message (import / download / sync), null when idle. */
    val busy: String? = null,
    val busyCancellable: Boolean = false,
    /** Result of the last task. */
    val message: String? = null,
) {
    val total: Long get() = counts.values.sum()
}

/**
 * Owns the offline cell pipeline: scanning, the multi-source tower database, learning from trusted
 * GPS, OpenCellID / Mozilla imports and two-way sync with a cell-sharing server.
 */
class CellManager(private val context: Context, private val scope: CoroutineScope, private val hub: PositioningHub, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("cells", Context.MODE_PRIVATE)

    /** A string resource in the current app language. */
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)
    val db = CellDatabase(context)
    private var lastCellLogMs = 0L
    private var lastLearnedFixMs = -1L
    private var task: Job? = null

    private val _status = MutableStateFlow(CellStatus())
    val status: StateFlow<CellStatus> = _status.asStateFlow()

    private val _towerLayer = MutableStateFlow(TowerLayer())
    val towerLayer: StateFlow<TowerLayer> = _towerLayer.asStateFlow()
    private var towerJob: Job? = null
    private var lastViewport: DoubleArray? = null
    private var lastQuery: DoubleArray? = null

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
        enabledRadios = ::enabledRadios,
    )

    init {
        scope.launch {
            runCatching {
                reloadCounts()
                installBundledIfNeeded()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _status.update { it.copy(preparation = Preparation.FAILED, message = str(R.string.task_failed, error.message.orEmpty())) }
            }
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
        lastQuery = null
        lastViewport?.let { v -> onViewport(v[0], v[1], v[2], v[3], v[4]) }
    }

    /** The configured country codes as numbers. */
    private fun mccSet(): Set<Int> = mccText().split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(255) }
    private fun mccText(): String = prefs.getString("mccs", "255").orEmpty()

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
                hasSyncKey = prefs.getString("sync_key", "").orEmpty().isNotBlank(),
                autoSync = prefs.getBoolean("auto_sync", false),
                lastSync = prefs.getString("last_sync_msg", null),
            )
        }
    }

    /** Re-count towers per source (a database query, so on the IO dispatcher). */
    private suspend fun reloadCounts() {
        val counts = withContext(Dispatchers.IO) { db.counts() }
        lastQuery = null // the database changed: the next viewport must re-query
        _status.update { it.copy(counts = counts) }
    }

    // ------------------------------------------------------------------ settings

    /** Random, install-scoped identifier sent to the sharing server (not tied to the phone or user). */
    private fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit { putString("device_id", it) }
    }

    /** The saved OpenCellID token / sync key, for pre-filling Settings fields. */
    fun savedToken(): String = prefs.getString("token", "").orEmpty()
    fun savedSyncKey(): String = prefs.getString("sync_key", "").orEmpty()

    /** Enable or disable learning tower positions from GPS. */
    fun setLearning(on: Boolean) = prefs.edit { putBoolean("learning", on) }

    /** Show or hide the tower layer on the map. */
    fun setShowTowers(on: Boolean) {
        prefs.edit { putBoolean("show_towers", on) }
        refresh()
        val v = lastViewport
        lastQuery = null
        if (on && v != null) {
            onViewport(v[0], v[1], v[2], v[3], v[4])
        } else if (!on) {
            _towerLayer.value = TowerLayer()
        }
    }

    /** Map camera settled: load towers for the visible area (debounced by cancelling the previous query). */
    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        lastViewport = doubleArrayOf(south, west, north, east, zoom)
        if (!prefs.getBoolean("show_towers", false)) return
        // While the camera follows the car it settles every tick; skip queries that would return the same towers.
        val q = lastQuery
        if (q != null && q.contains(south, west, north, east) && abs(zoom - q[4]) < 0.5 && !_towerLayer.value.truncated) return
        // Query a margin around the view so small moves stay inside it.
        val padLat = (north - south) * 0.5
        val padLon = (east - west) * 0.5
        lastQuery = if (zoom >= MIN_TOWER_ZOOM) doubleArrayOf(south - padLat, west - padLon, north + padLat, east + padLon, zoom) else null
        towerJob?.cancel()
        if (zoom < MIN_TOWER_ZOOM) {
            towerJob = scope.launch {
                _towerLayer.value = TowerLayer(visible = withContext(Dispatchers.IO) { visibleTowers() }, zoomTooLow = true)
            }
            return
        }
        towerJob = scope.launch {
            val layer = withContext(Dispatchers.IO) {
                val (rows, truncated) = db.towersIn(south - padLat, west - padLon, north + padLat, east + padLon, MAX_TOWERS_ON_MAP, enabledRadios())
                TowerLayer(rows.map { it.first }, visibleTowers(), truncated)
            }
            _towerLayer.value = layer
        }
    }

    /** This query box (south, west, north, east, zoom) covers the given bounds. */
    private fun DoubleArray.contains(south: Double, west: Double, north: Double, east: Double) = south >= this[0] && west >= this[1] && north <= this[2] && east <= this[3]

    /** Database entries for the cells the phone sees now (for the red rings on the map). */
    private fun visibleTowers(): List<CellTower> = scanner.lastUsable.mapNotNull { runCatching { db.resolve(it.key)?.first }.getOrNull() }

    /** Save the sharing-server and country settings from the Settings screen. */
    fun saveSettings(syncUrl: String, syncKey: String, autoSync: Boolean, mccs: String) {
        prefs.edit {
            putString("sync_url", syncUrl.trim())
            putString("sync_key", syncKey.trim())
            putBoolean("auto_sync", autoSync)
            putString("mccs", mccs.trim().ifEmpty { "255" })
        }
        refresh()
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

    /** Import an OpenCellID / Mozilla CSV (plain or gzip) picked by the user, filtered to the configured MCCs. */
    fun importFile(open: () -> InputStream?) = runTask(str(R.string.task_importing_towers, 0), cancellable = true) {
        val ctx = coroutineContext
        val mccs = mccSet()
        val kept = withContext(Dispatchers.IO) {
            (open() ?: error("cannot open file")).use { input ->
                db.importStream(CellSource.OPENCELLID, input, mccs) { read, kept ->
                    progress(str(R.string.task_importing, kept, read))
                    ctx.isActive
                }
            }
        }
        str(R.string.task_imported, kept)
    }

    /** Download and import the OpenCellID export for the first configured MCC with the user's token. */
    fun downloadOpenCellId(token: String) {
        if (token.isBlank()) return
        prefs.edit { putString("token", token.trim()) }
        runTask(str(R.string.task_downloading_ocid, 0)) {
            var total = 0L
            for (mcc in mccSet()) {
                total += withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "ocid-$mcc.csv.gz")
                    try {
                        OpenCellIdDownloader.download(token, mcc, file) { b -> progress(str(R.string.task_downloading_ocid, (b / 1024).toInt())) }
                        file.inputStream().use {
                            db.importStream(CellSource.OPENCELLID, it) { _, kept ->
                                progress(str(R.string.task_importing_towers, kept))
                                true
                            }
                        }
                    } finally {
                        file.delete()
                    }
                }
            }
            str(R.string.task_ocid_done, total)
        }
    }

    /**
     * Stream the Mozilla Location Service final export (~1.5 GB, public domain) from archive.org,
     * keeping only the configured MCCs. Nothing large is written to storage; the download resumes
     * automatically after network drops.
     */
    fun downloadMozilla() = runTask(str(R.string.task_connecting), cancellable = true) {
        val ctx = coroutineContext
        val mccs = mccSet()
        val kept = withContext(Dispatchers.IO) {
            var lastReport = 0L
            val input = ResumableHttpInputStream(MOZILLA_URL, onProgress = { bytes, total ->
                if (bytes - lastReport >= 4L * 1024 * 1024) {
                    lastReport = bytes
                    val pct = if (total > 0) (bytes * 100 / total).toInt() else 0
                    progress(str(R.string.task_mozilla_progress, (bytes / (1024 * 1024)).toInt(), (total / (1024 * 1024)).toInt(), pct))
                }
            })
            input.use {
                db.importStream(CellSource.MOZILLA, it, mccs) { _, _ -> ctx.isActive }
            }
        }
        str(R.string.task_mozilla_done, kept)
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
    private fun bundledAssetAndHash(): Pair<String, String>? {
        val asset = BUNDLED_ASSETS.firstOrNull { name -> runCatching { context.assets.open(name).close() }.isSuccess } ?: return null
        val hash = run {
            context.assets.open(asset).use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }
        return asset to hash
    }

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

    /**
     * Export all sources, deduplicated, to the app's external files dir
     * (Android/data/org.imunav.app/files/cells-export.csv.gz) — readable over adb/USB.
     */
    fun exportDatabase() = runTask(str(R.string.task_exporting, 0)) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(dir, "cells-export.csv.gz")
        val tmp = File(dir, "cells-export.csv.gz.tmp")
        val n = withContext(Dispatchers.IO) {
            val count = tmp.outputStream().use { db.exportMerged(it) { k -> progress(str(R.string.task_exporting, k)) } }
            tmp.renameTo(target)
            count
        }
        str(R.string.task_exported, n, (target.length() / 1024).toInt())
    }

    /** Upload learned towers, then download merged data into the SHARED source. */
    fun sync(auto: Boolean = false) {
        val url = prefs.getString("sync_url", "").orEmpty()
        if (url.isBlank()) {
            if (!auto) _status.update { it.copy(message = str(R.string.task_sync_no_url)) }
            return
        }
        runTask(str(R.string.task_syncing)) {
            val started = System.currentTimeMillis()
            val client = CellSyncClient(url, savedSyncKey(), deviceId())
            val (uploaded, downloaded) = withContext(Dispatchers.IO) {
                val pending = db.learnedSince(prefs.getLong("last_upload_ms", 0))
                progress(str(R.string.task_uploading, pending.size))
                val up = client.upload(pending)
                prefs.edit { putLong("last_upload_ms", started) }

                progress(str(R.string.task_downloading_shared))
                val batch = ArrayList<CellTower>()
                val since = prefs.getLong("last_download_s", 0)
                client.download(mccSet(), since) { batch += it }
                db.upsert(CellSource.SHARED, batch)
                prefs.edit { putLong("last_download_s", started / 1000 - 60) }
                up to batch.size
            }
            val msg = str(R.string.task_sync_done, uploaded, downloaded)
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
        private val BUNDLED_ASSETS = listOf("cells/bundled-cells.csv.gz", "cells/bundled-cells.csv")
        const val MOZILLA_URL = "https://archive.org/download/MLS_Full_Cell_Export_Final/MLS-full-cell-export-final.csv.gz"
        private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
