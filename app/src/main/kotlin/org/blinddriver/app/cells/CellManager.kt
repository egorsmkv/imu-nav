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
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.ResumableHttpInputStream
import org.blinddriver.core.gnss.PositioningHub
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Offline cell positioning status for the UI. */
data class CellStatus(
    val seen: Int = 0,
    val located: Int = 0,
    val accuracyM: Double? = null,
    val counts: Map<CellSource, Long> = emptyMap(),
    val learning: Boolean = true,
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
    )

    init {
        scope.launch {
            reloadCounts()
            if (prefs.getBoolean("auto_sync", false) && System.currentTimeMillis() - prefs.getLong("last_sync_ms", 0) > AUTO_SYNC_INTERVAL_MS) sync(auto = true)
        }
    }

    private fun mccSet(): Set<Int> = mccText().split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(255) }
    private fun mccText(): String = prefs.getString("mccs", "255").orEmpty()

    /** Called ~1/s from the UI refresh loop. */
    fun refresh() {
        maybeLearn()
        val fix = scanner.lastFix
        _status.update {
            it.copy(
                seen = scanner.lastObservations.size,
                located = fix?.towersUsed ?: 0,
                accuracyM = fix?.accuracyM,
                learning = prefs.getBoolean("learning", true),
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
        const val MOZILLA_URL = "https://archive.org/download/MLS_Full_Cell_Export_Final/MLS-full-cell-export-final.csv.gz"
        private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
