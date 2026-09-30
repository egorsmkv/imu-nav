package org.imunav.app.cells

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.core.cells.CellContribution
import org.imunav.core.cells.CellFix
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.CellUsageCsv
import org.imunav.core.cells.CellUsageRecord
import org.imunav.core.cells.Radio
import org.imunav.core.gnss.RawFix
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Small UI snapshot; the complete history stays on disk rather than growing in memory. */
data class CellUsageStatus(val total: Long = 0, val recent: List<CellUsageRecord> = emptyList(), val failed: Boolean = false)

/**
 * Persistent history of towers used to produce CELL fixes, including while no route is active.
 * Writes run on the scanner worker; exporting streams a stable id-bounded snapshot on IO.
 * This separate database is intentionally unaffected by tower-source imports and resets.
 */
class CellUsageHistory(private val context: Context, scope: CoroutineScope, private val log: (String) -> Unit) {
    private val database = UsageDatabase(context)
    private val sessionId = UUID.randomUUID().toString()
    private val _status = MutableStateFlow(CellUsageStatus())
    val status = _status.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { reload() }.onFailure { reportFailure(it) }
        }
    }

    /** Record every contributing tower, not unknown cells or positioner-rejected outliers (worker thread). */
    fun record(raw: RawFix, fix: CellFix) {
        runCatching {
            database.writableDatabase.transaction {
                fix.contributions.forEach { contribution ->
                    val record = CellUsageRecord(sessionId, raw.timeMs, raw.elapsedMs, contribution, fix.lat, fix.lon, fix.accuracyM)
                    insertOrThrow("usage", null, values(record))
                }
            }
            reload()
        }.onFailure { reportFailure(it) }
    }

    /** History failures must never suppress a positioning fix, including when storage is full. */
    private fun reportFailure(error: Throwable) {
        _status.value = _status.value.copy(failed = true)
        log("cell_history_failed type=${error.javaClass.simpleName}")
    }

    /** Query only a short preview for Compose; no history database work is done on the main thread. */
    private fun reload() {
        val db = database.readableDatabase
        val total = db.rawQuery("SELECT COUNT(*) FROM usage", null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        val recent = db.rawQuery("SELECT $COLUMNS FROM usage ORDER BY id DESC LIMIT $PREVIEW_ROWS", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(readRecord(cursor)) }
        }
        _status.value = CellUsageStatus(total, recent)
    }

    /** Produce a unique private-cache CSV; concurrent scans cannot add rows to this export midway through. */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val db = database.readableDatabase
        val lastId = db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM usage", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        val directory = File(context.cacheDir, "cell-history-shares")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create history export directory" }
        val file = File.createTempFile("cell-history-", ".csv", directory)
        var complete = false
        try {
            file.bufferedWriter().use { writer ->
                writer.append(CellUsageCsv.HEADER).append('\n')
                db.rawQuery("SELECT $COLUMNS FROM usage WHERE id <= ? ORDER BY id", arrayOf(lastId.toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        coroutineContext.ensureActive()
                        CellUsageCsv.writeRecord(writer, readRecord(cursor))
                    }
                }
            }
            complete = true
            file
        } finally {
            if (!complete) file.delete() // This incomplete temporary export has never been shared.
        }
    }

    /** Store the observation and the geometry used at the time, even if tower coordinates later change. */
    private fun values(record: CellUsageRecord) = ContentValues().apply {
        val observation = record.contribution.observation
        val tower = record.contribution.tower
        val key = observation.key
        put("session", record.sessionId)
        put("time", record.timeMs)
        put("elapsed", record.elapsedMs)
        put("radio", key.radio.name)
        put("mcc", key.mcc)
        put("mnc", key.mnc)
        put("area", key.area)
        put("cid", key.cid)
        put("dbm", observation.dbm)
        put("serving", if (observation.serving) 1 else 0)
        put("ta", observation.timingAdvance)
        put("lat", tower.lat)
        put("lon", tower.lon)
        put("range", tower.rangeM)
        put("fix_lat", record.fixLat)
        put("fix_lon", record.fixLon)
        put("accuracy", record.accuracyM)
    }

    /** Columns follow the export order so nullable modem fields remain distinct from zero. */
    private fun readRecord(cursor: Cursor): CellUsageRecord {
        val key = CellKey(Radio.valueOf(cursor.getString(3)), cursor.getInt(4), cursor.getInt(5), cursor.getInt(6), cursor.getLong(7))
        val observation = CellObservation(key, if (cursor.isNull(8)) null else cursor.getInt(8), cursor.getInt(9) != 0, if (cursor.isNull(10)) null else cursor.getInt(10))
        val tower = CellTower(key, cursor.getDouble(11), cursor.getDouble(12), cursor.getDouble(13), 0)
        return CellUsageRecord(
            cursor.getString(0),
            cursor.getLong(1),
            cursor.getLong(2),
            CellContribution(observation, tower),
            cursor.getDouble(14),
            cursor.getDouble(15),
            cursor.getDouble(16),
        )
    }

    private companion object {
        const val PREVIEW_ROWS = 20
        const val COLUMNS = "session,time,elapsed,radio,mcc,mnc,area,cid,dbm,serving,ta,lat,lon,range,fix_lat,fix_lon,accuracy"
    }
}

/** Independent schema keeps usage history intact when the positioning database is reset or upgraded. */
private class UsageDatabase(context: Context) : SQLiteOpenHelper(context, "cell-usage.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE usage (id INTEGER PRIMARY KEY, session TEXT NOT NULL, time INTEGER NOT NULL, elapsed INTEGER NOT NULL, " +
                "radio TEXT NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, area INTEGER NOT NULL, cid INTEGER NOT NULL, " +
                "dbm INTEGER, serving INTEGER NOT NULL, ta INTEGER, lat REAL NOT NULL, lon REAL NOT NULL, range REAL NOT NULL, " +
                "fix_lat REAL NOT NULL, fix_lon REAL NOT NULL, accuracy REAL NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
