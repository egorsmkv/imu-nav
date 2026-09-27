package org.blinddriver.app.cells

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blinddriver.core.cells.CellSyncClient
import org.blinddriver.core.cells.Radio
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.ResumableHttpInputStream
import org.blinddriver.core.gnss.PositioningHub
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Towers to draw on the map for the current viewport. */
data class TowerLayer(
    val towers: List<CellTower> = emptyList(),
    /** Towers of the cells the phone sees right now (exact or site match). */
    val visible: List<CellTower> = emptyList(),
    val truncated: Boolean = false,
    /** Map is zoomed out too far to show towers. */
    val zoomTooLow: Boolean = false,
)

/** Offline cell positioning status for the UI. */
data class CellStatus(
    val seen: Int = 0,
    val located: Int = 0,
    val accuracyM: Double? = null,
    val counts: Map<CellSource, Long> = emptyMap(),
    val learning: Boolean = true,
    val showTowers: Boolean = false,
    /** Cell types used for positioning and drawn on the map. */
    val radios: Set<Radio> = CellManager.DEFAULT_RADIOS,
    val hasToken: Boolean = false,
    val mccs: String = "255",
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
class CellManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: PositioningHub,
    private val log: (String) -> Unit,
) {
    private val prefs = context.getSharedPreferences("cells", Context.MODE_PRIVATE)
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

    val scanner = CellScanner(
        context,
        db,
        onFix = { raw, fix ->
            hub.onFix(raw)
            if (raw.elapsedMs - lastCellLogMs >= 30_000) {
                lastCellLogMs = raw.elapsedMs
                log("cell_fix towers=${fix.towersUsed}/${fix.towersSeen} acc=${fix.accuracyM.toInt()} %.5f %.5f".format(fix.lat, fix.lon))
            }
        },
        log = log,
        enabledRadios = ::enabledRadios,
    )

    init {
        scope.launch {
            reloadCounts()
            installBundledIfNeeded()
            if (prefs.getBoolean("auto_sync", false) && System.currentTimeMillis() - prefs.getLong("last_sync_ms", 0) > AUTO_SYNC_INTERVAL_MS) sync(auto = true)
        }
    }

    fun enabledRadios(): Set<Radio> =
        prefs.getString("radios", null)?.split(',')?.mapNotNull { n -> Radio.entries.firstOrNull { it.name == n } }?.toSet()
            ?: DEFAULT_RADIOS

    fun setRadioEnabled(radio: Radio, on: Boolean) {
        val set = if (on) enabledRadios() + radio else enabledRadios() - radio
        prefs.edit().putString("radios", set.joinToString(",") { it.name }).apply()
        log("cell_radios ${set.joinToString(",") { it.name }}")
        refresh()
        lastViewport?.let { v -> onViewport(v[0], v[1], v[2], v[3], v[4]) }
    }

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

    private suspend fun reloadCounts() {
        val counts = withContext(Dispatchers.IO) { db.counts() }
        _status.update { it.copy(counts = counts) }
    }

    // ------------------------------------------------------------------ settings

    fun savedToken(): String = prefs.getString("token", "").orEmpty()
    fun savedSyncKey(): String = prefs.getString("sync_key", "").orEmpty()

    fun setLearning(on: Boolean) = prefs.edit().putBoolean("learning", on).apply()

    fun setShowTowers(on: Boolean) {
        prefs.edit().putBoolean("show_towers", on).apply()
        refresh()
        val v = lastViewport
        if (on && v != null) onViewport(v[0], v[1], v[2], v[3], v[4]) else if (!on) _towerLayer.value = TowerLayer()
    }

    /** Map camera settled: load towers for the visible area (debounced by cancelling the previous query). */
    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        lastViewport = doubleArrayOf(south, west, north, east, zoom)
        if (!prefs.getBoolean("show_towers", false)) return
        towerJob?.cancel()
        if (zoom < MIN_TOWER_ZOOM) {
            _towerLayer.value = TowerLayer(visible = visibleTowers(), zoomTooLow = true)
            return
        }
        towerJob = scope.launch {
            val layer = withContext(Dispatchers.IO) {
                val (rows, truncated) = db.towersIn(south, west, north, east, MAX_TOWERS_ON_MAP, enabledRadios())
                TowerLayer(rows.map { it.first }, visibleTowers(), truncated)
            }
            _towerLayer.value = layer
        }
    }

    private fun visibleTowers(): List<CellTower> =
        scanner.lastUsable.mapNotNull { runCatching { db.resolve(it.key)?.first }.getOrNull() }

    fun saveSettings(syncUrl: String, syncKey: String, autoSync: Boolean, mccs: String) {
        prefs.edit()
            .putString("sync_url", syncUrl.trim())
            .putString("sync_key", syncKey.trim())
            .putBoolean("auto_sync", autoSync)
            .putString("mccs", mccs.trim().ifEmpty { "255" })
            .apply()
        refresh()
    }

    // ------------------------------------------------------------------ long-running tasks

    private fun runTask(start: String, cancellable: Boolean = false, block: suspend () -> String) {
        if (task?.isActive == true) return
        task = scope.launch {
            _status.update { it.copy(busy = start, busyCancellable = cancellable, message = null) }
            val msg = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Cancelled"
            } catch (e: InterruptedException) {
                "Cancelled"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            log("cells_task ${msg.lowercase()}")
            reloadCounts()
            _status.update { it.copy(busy = null, busyCancellable = false, message = msg) }
        }
    }

    fun cancelTask() {
        task?.cancel()
    }

    private fun progress(text: String) {
        scope.launch { _status.update { it.copy(busy = text) } }
    }

    /** Import an OpenCellID / Mozilla CSV (plain or gzip) picked by the user, filtered to the configured MCCs. */
    fun importFile(open: () -> InputStream?) = runTask("Importing…", cancellable = true) {
        val ctx = coroutineContext
        val mccs = mccSet()
        val kept = withContext(Dispatchers.IO) {
            (open() ?: error("cannot open file")).use { input ->
                db.importStream(CellSource.OPENCELLID, input, mccs) { read, kept ->
                    progress("Importing… $kept of $read rows")
                    ctx.isActive
                }
            }
        }
        "Imported $kept towers (MCC ${mccs.joinToString()})"
    }

    /** Download and import the OpenCellID export for the first configured MCC with the user's token. */
    fun downloadOpenCellId(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString("token", token.trim()).apply()
        runTask("Downloading OpenCellID…") {
            var total = 0L
            for (mcc in mccSet()) {
                total += withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "ocid-$mcc.csv.gz")
                    try {
                        OpenCellIdDownloader.download(token, mcc, file) { b -> progress("Downloading OpenCellID $mcc… ${b / 1024} KB") }
                        file.inputStream().use { db.importStream(CellSource.OPENCELLID, it) { _, kept -> progress("Importing… $kept towers"); true } }
                    } finally {
                        file.delete()
                    }
                }
            }
            "OpenCellID: imported $total towers"
        }
    }

    /**
     * Stream the Mozilla Location Service final export (~1.5 GB, public domain) from archive.org,
     * keeping only the configured MCCs. Nothing large is written to storage; the download resumes
     * automatically after network drops.
     */
    fun downloadMozilla() = runTask("Connecting to archive.org…", cancellable = true) {
        val ctx = coroutineContext
        val mccs = mccSet()
        val kept = withContext(Dispatchers.IO) {
            var lastReport = 0L
            val input = ResumableHttpInputStream(MOZILLA_URL, onProgress = { bytes, total ->
                if (bytes - lastReport >= 4L * 1024 * 1024) {
                    lastReport = bytes
                    val pct = if (total > 0) " (${bytes * 100 / total}%)" else ""
                    progress("Mozilla: ${bytes / (1024 * 1024)} of ${total / (1024 * 1024)} MB$pct")
                }
            })
            input.use {
                db.importStream(CellSource.MOZILLA, it, mccs) { _, _ -> ctx.isActive }
            }
        }
        "Mozilla: imported $kept towers (MCC ${mccs.joinToString()})"
    }

    /**
     * Import the tower database shipped in the APK (assets/cells/bundled-cells.csv.gz) into the
     * BUNDLED source on first run, and again only when an app update ships a different file
     * (detected by SHA-256 of the asset).
     */
    private fun installBundledIfNeeded(force: Boolean = false) {
        // The Android build un-gzips *.gz assets and drops the extension, so accept either name.
        val asset = BUNDLED_ASSETS.firstOrNull { name -> runCatching { context.assets.open(name).close() }.isSuccess } ?: return
        val hash = runCatching {
            context.assets.open(asset).use { input ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return
        if (!force && prefs.getString("bundled_sha256", null) == hash) return
        runTask("Preparing built-in towers…") {
            val n = withContext(Dispatchers.IO) {
                db.clear(CellSource.BUNDLED)
                context.assets.open(asset).use { input ->
                    db.importStream(CellSource.BUNDLED, input) { _, kept -> progress("Preparing built-in towers… $kept"); true }
                }
            }
            prefs.edit().putString("bundled_sha256", hash).apply()
            "Built-in database ready: $n towers"
        }
    }

    /**
     * Delete downloaded/imported towers (all sources except, optionally, the ones this phone learned),
     * compact the file, then re-import the database bundled with the APK.
     */
    fun resetDatabase(deleteLearned: Boolean) {
        runTask("Clearing tower database…") {
            withContext(Dispatchers.IO) {
                for (s in CellSource.entries) {
                    if (s == CellSource.LEARNED && !deleteLearned) continue
                    progress("Clearing ${s.label}…")
                    db.clear(s)
                }
                progress("Compacting database…")
                db.vacuum()
            }
            prefs.edit()
                .remove("bundled_sha256")
                .putLong("last_download_s", 0) // next sync downloads the full shared dataset again
                .apply()
            if (deleteLearned) prefs.edit().putLong("last_upload_ms", 0).apply()
            log("cells_reset learned_deleted=$deleteLearned")
            "Tower database cleared${if (deleteLearned) "" else " (learned towers kept)"}"
        }
        // Runs after the clearing task finishes (tasks don't overlap).
        scope.launch {
            task?.join()
            installBundledIfNeeded(force = true)
        }
    }

    /**
     * Export all sources, deduplicated, to the app's external files dir
     * (Android/data/org.blinddriver.app/files/cells-export.csv.gz) — readable over adb/USB.
     */
    fun exportDatabase() = runTask("Exporting…") {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(dir, "cells-export.csv.gz")
        val tmp = File(dir, "cells-export.csv.gz.tmp")
        val n = withContext(Dispatchers.IO) {
            val count = tmp.outputStream().use { db.exportMerged(it) { k -> progress("Exporting… $k towers") } }
            tmp.renameTo(target)
            count
        }
        "Exported $n towers to ${target.absolutePath} (${target.length() / 1024} KB)"
    }

    /** Upload learned towers, then download merged data into the SHARED source. */
    fun sync(auto: Boolean = false) {
        val url = prefs.getString("sync_url", "").orEmpty()
        if (url.isBlank()) {
            if (!auto) _status.update { it.copy(message = "Set a sync server URL first") }
            return
        }
        runTask(if (auto) "Auto-sync…" else "Syncing…") {
            val started = System.currentTimeMillis()
            val client = CellSyncClient(url, savedSyncKey())
            val (uploaded, downloaded) = withContext(Dispatchers.IO) {
                val pending = db.learnedSince(prefs.getLong("last_upload_ms", 0))
                progress("Uploading ${pending.size} learned towers…")
                val up = client.upload(pending)
                prefs.edit().putLong("last_upload_ms", started).apply()

                progress("Downloading shared towers…")
                val batch = ArrayList<CellTower>()
                val since = prefs.getLong("last_download_s", 0)
                client.download(mccSet(), since) { batch += it }
                db.upsert(CellSource.SHARED, batch)
                prefs.edit().putLong("last_download_s", started / 1000 - 60).apply()
                up to batch.size
            }
            val msg = "Sync: sent $uploaded, received $downloaded towers"
            prefs.edit().putLong("last_sync_ms", started)
                .putString("last_sync_msg", "$msg (${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(started))})").apply()
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
        val obs = scanner.lastObservations
        if (acc > 30f || obs.isEmpty() || kotlin.math.abs(scanner.lastScanMs - good.elapsedMs) > 10_000) return
        if (lastLearnedFixMs > 0 && good.elapsedMs - lastLearnedFixMs < 20_000) return
        lastLearnedFixMs = good.elapsedMs
        scope.launch {
            withContext(Dispatchers.IO) { db.learn(obs.map { it.key }, good.lat, good.lon, acc.toDouble()) }
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
