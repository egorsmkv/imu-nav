package org.imunav.app.cells

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.core.cells.CellSyncClient
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.ResumableHttpInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Performs cell imports, exports, and sync I/O while the manager owns task lifetime and UI state. */
internal class CellTransfers(
    private val context: Context,
    private val db: CellDatabase,
    private val prefs: SharedPreferences,
    private val auth: CellAuth,
    private val progress: (String) -> Unit,
) {
    /** Import a user-selected CSV, stopping promptly when its owning task is cancelled. */
    suspend fun importFile(open: () -> InputStream?, mccs: Set<Int>): Long {
        val owner = coroutineContext
        return withContext(Dispatchers.IO) {
            (open() ?: error("cannot open file")).use { input ->
                db.importStream(CellSource.OPENCELLID, input, mccs) { read, kept ->
                    progress(context.getString(R.string.task_importing, kept, read))
                    owner.isActive
                }
            }
        }
    }

    /** Download each configured country export and remove its temporary archive after import. */
    suspend fun downloadOpenCellId(token: String, mccs: Set<Int>): Long {
        var total = 0L
        for (mcc in mccs) {
            total += withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "ocid-$mcc.csv.gz")
                try {
                    OpenCellIdDownloader.download(token, mcc, file) { bytes ->
                        progress(context.getString(R.string.task_downloading_ocid, (bytes / 1024).toInt()))
                    }
                    file.inputStream().use { input ->
                        db.importStream(CellSource.OPENCELLID, input) { _, kept ->
                            progress(context.getString(R.string.task_importing_towers, kept))
                            true
                        }
                    }
                } finally {
                    file.delete()
                }
            }
        }
        return total
    }

    /** Stream the Mozilla archive without staging its full contents on the phone. */
    suspend fun downloadMozilla(mccs: Set<Int>): Long {
        val owner = coroutineContext
        return withContext(Dispatchers.IO) {
            var lastReport = 0L
            val input = ResumableHttpInputStream(CellManager.MOZILLA_URL, onProgress = { bytes, total ->
                if (bytes - lastReport >= 4L * 1024 * 1024) {
                    lastReport = bytes
                    val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
                    progress(context.getString(R.string.task_mozilla_progress, (bytes / (1024 * 1024)).toInt(), (total / (1024 * 1024)).toInt(), percent))
                }
            })
            input.use { db.importStream(CellSource.MOZILLA, it, mccs) { _, _ -> owner.isActive } }
        }
    }

    /** Hash the bundled archive on an I/O thread before the manager decides whether to install it. */
    fun bundledAssetAndHash(): Pair<String, String>? {
        val asset = BUNDLED_ASSETS.firstOrNull { name -> runCatching { context.assets.open(name).close() }.isSuccess } ?: return null
        val hash = context.assets.open(asset).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        return asset to hash
    }

    /** Export a merged snapshot to external app storage for USB or adb retrieval. */
    suspend fun exportDatabase(): ExportResult {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(directory, "cells-export.csv.gz")
        val temporary = File(directory, "cells-export.csv.gz.tmp")
        val count = withContext(Dispatchers.IO) {
            val exported = temporary.outputStream().use { db.exportMerged(it) { kept -> progress(context.getString(R.string.task_exporting, kept)) } }
            temporary.renameTo(target)
            exported
        }
        return ExportResult(count, target.length() / 1024)
    }

    /** Upload learned towers and then import the shared snapshot into its own source. */
    suspend fun sync(url: String, deviceId: String, mccs: Set<Int>, startedMs: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val accessToken = if (auth.email != null) auth.accessToken(url) else null
        val client = CellSyncClient(url, accessToken, deviceId)
        val pending = db.learnedSince(prefs.getLong("last_upload_ms", 0))
        val uploaded = if (accessToken != null) {
            progress(context.getString(R.string.task_uploading, pending.size))
            client.upload(pending).also { prefs.edit { putLong("last_upload_ms", startedMs) } }
        } else {
            0
        }

        progress(context.getString(R.string.task_downloading_shared))
        val batch = ArrayList<CellTower>()
        val since = prefs.getLong("last_download_s", 0)
        client.download(mccs, since) { batch += it }
        db.upsert(CellSource.SHARED, batch)
        prefs.edit { putLong("last_download_s", startedMs / 1000 - 60) }
        uploaded to batch.size
    }

    /** Export count and size, used only to build the existing user-facing result. */
    data class ExportResult(val count: Long, val sizeKib: Long)

    private companion object {
        val BUNDLED_ASSETS = listOf("cells/bundled-cells.csv.gz", "cells/bundled-cells.csv")
    }
}
