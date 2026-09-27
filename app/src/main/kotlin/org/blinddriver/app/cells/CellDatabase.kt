package org.blinddriver.app.cells

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.blinddriver.core.cells.CellCsv
import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellLearning
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.CellTowerDb
import org.blinddriver.core.cells.Radio
import java.io.InputStream

/** Where a tower location came from. Lookup order = declaration order. */
enum class CellSource(val table: String, val label: String) {
    /** Merged community data from the configured sync server (freshest). */
    SHARED("shared", "sync server"),
    OPENCELLID("imported", "OpenCellID"),

    /** Located by this phone from trusted GPS. */
    LEARNED("learned", "learned"),

    /** Mozilla Location Service final export (public domain, March 2024). */
    MOZILLA("mozilla", "Mozilla"),
}

/** Offline cell tower locations from several sources, one table each. */
class CellDatabase(context: Context) : SQLiteOpenHelper(context, "cells.db", null, 2), CellTowerDb {

    override fun onCreate(db: SQLiteDatabase) {
        for (s in CellSource.entries) createTable(db, s.table)
        db.execSQL("ALTER TABLE learned ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTable(db, CellSource.SHARED.table)
            createTable(db, CellSource.MOZILLA.table)
            // Existing learned rows count as not yet uploaded.
            db.execSQL("ALTER TABLE learned ADD COLUMN updated INTEGER NOT NULL DEFAULT 1")
        }
    }

    private fun createTable(db: SQLiteDatabase, table: String) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS $table (radio INTEGER NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, area INTEGER NOT NULL, " +
            "cid INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, range REAL NOT NULL, samples INTEGER NOT NULL, " +
            "PRIMARY KEY (mcc, mnc, area, cid, radio)) WITHOUT ROWID"
    )

    fun counts(): Map<CellSource, Long> {
        val db = readableDatabase
        return CellSource.entries.associateWith { s ->
            db.rawQuery("SELECT COUNT(*) FROM ${s.table}", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }
    }

    override fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower> {
        val out = HashMap<CellKey, CellTower>()
        val db = readableDatabase
        for (k in keys) {
            for (s in CellSource.entries) {
                val t = find(db, s.table, k) ?: continue
                out[k] = t
                break
            }
        }
        return out
    }

    /** Which source answered for each key (for diagnostics). */
    fun sourcesOf(keys: Collection<CellKey>): Map<CellKey, CellSource> {
        val db = readableDatabase
        return keys.mapNotNull { k -> CellSource.entries.firstOrNull { find(db, it.table, k) != null }?.let { k to it } }.toMap()
    }

    private fun find(db: SQLiteDatabase, table: String, k: CellKey): CellTower? =
        db.rawQuery(
            "SELECT lat, lon, range, samples FROM $table WHERE mcc=? AND mnc=? AND area=? AND cid=? AND radio=?",
            arrayOf(k.mcc.toString(), k.mnc.toString(), k.area.toString(), k.cid.toString(), k.radio.ordinal.toString()),
        ).use { c -> if (c.moveToFirst()) CellTower(k, c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getInt(3)) else null }

    /** Update learned towers for every cell heard at a trusted position. */
    fun learn(keys: Collection<CellKey>, lat: Double, lon: Double, accuracyM: Double, nowMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (k in keys) {
                val t = CellLearning.update(find(db, CellSource.LEARNED.table, k), k, lat, lon, accuracyM)
                db.execSQL(
                    "INSERT OR REPLACE INTO learned (radio, mcc, mnc, area, cid, lat, lon, range, samples, updated) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(t.key.radio.ordinal, t.key.mcc, t.key.mnc, t.key.area, t.key.cid, t.lat, t.lon, t.rangeM, t.samples, nowMs),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Learned towers changed after [sinceMs] — what still needs uploading. */
    fun learnedSince(sinceMs: Long): List<CellTower> {
        val out = ArrayList<CellTower>()
        readableDatabase.rawQuery(
            "SELECT radio, mcc, mnc, area, cid, lat, lon, range, samples FROM learned WHERE updated > ?",
            arrayOf(sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val key = CellKey(Radio.entries[c.getInt(0)], c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4))
                out += CellTower(key, c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getInt(8))
            }
        }
        return out
    }

    /**
     * Stream towers into [source]'s table in batched transactions.
     * @param mccs keep only these country codes (null = all)
     * @param onProgress called every batch with (rows read, rows kept); return false to cancel
     * @return rows kept
     */
    fun importStream(
        source: CellSource,
        input: InputStream,
        mccs: Set<Int>? = null,
        onProgress: (read: Long, kept: Long) -> Boolean = { _, _ -> true },
    ): Long {
        val db = writableDatabase
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO ${source.table} (radio, mcc, mnc, area, cid, lat, lon, range, samples) VALUES (?,?,?,?,?,?,?,?,?)"
        )
        var read = 0L
        var kept = 0L
        db.beginTransaction()
        try {
            CellCsv.read(input) { t ->
                read++
                if (mccs == null || t.key.mcc in mccs) {
                    stmt.clearBindings()
                    stmt.bindLong(1, t.key.radio.ordinal.toLong())
                    stmt.bindLong(2, t.key.mcc.toLong())
                    stmt.bindLong(3, t.key.mnc.toLong())
                    stmt.bindLong(4, t.key.area.toLong())
                    stmt.bindLong(5, t.key.cid)
                    stmt.bindDouble(6, t.lat)
                    stmt.bindDouble(7, t.lon)
                    stmt.bindDouble(8, t.rangeM)
                    stmt.bindLong(9, t.samples.toLong())
                    stmt.executeInsert()
                    kept++
                }
                if (read % 50_000 == 0L) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    if (!onProgress(read, kept)) throw InterruptedException("cancelled")
                    db.beginTransaction()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(read, kept)
        return kept
    }

    fun upsert(source: CellSource, towers: List<CellTower>) {
        if (towers.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in towers) {
                db.execSQL(
                    "INSERT OR REPLACE INTO ${source.table} (radio, mcc, mnc, area, cid, lat, lon, range, samples) VALUES (?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(t.key.radio.ordinal, t.key.mcc, t.key.mnc, t.key.area, t.key.cid, t.lat, t.lon, t.rangeM, t.samples),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear(source: CellSource) = writableDatabase.execSQL("DELETE FROM ${source.table}")
}
