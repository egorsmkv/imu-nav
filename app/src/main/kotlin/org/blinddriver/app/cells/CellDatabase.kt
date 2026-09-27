package org.blinddriver.app.cells

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellLearning
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.CellTowerDb
import org.blinddriver.core.cells.OpenCellIdCsv
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Offline cell tower locations: `imported` holds OpenCellID data, `learned` holds towers located
 * by this phone from trusted GPS fixes. Lookups prefer imported rows.
 */
class CellDatabase(context: Context) : SQLiteOpenHelper(context, "cells.db", null, 1), CellTowerDb {

    override fun onCreate(db: SQLiteDatabase) {
        for (table in listOf(IMPORTED, LEARNED)) {
            db.execSQL(
                "CREATE TABLE $table (radio INTEGER NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, area INTEGER NOT NULL, " +
                    "cid INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, range REAL NOT NULL, samples INTEGER NOT NULL, " +
                    "PRIMARY KEY (mcc, mnc, area, cid, radio)) WITHOUT ROWID"
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    data class Counts(val imported: Long, val learned: Long)

    fun counts(): Counts {
        val db = readableDatabase
        fun count(t: String) = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return Counts(count(IMPORTED), count(LEARNED))
    }

    override fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower> {
        val out = HashMap<CellKey, CellTower>()
        val db = readableDatabase
        for (k in keys) {
            (find(db, IMPORTED, k) ?: find(db, LEARNED, k))?.let { out[k] = it }
        }
        return out
    }

    private fun find(db: SQLiteDatabase, table: String, k: CellKey): CellTower? =
        db.rawQuery(
            "SELECT lat, lon, range, samples FROM $table WHERE mcc=? AND mnc=? AND area=? AND cid=? AND radio=?",
            arrayOf(k.mcc.toString(), k.mnc.toString(), k.area.toString(), k.cid.toString(), k.radio.ordinal.toString()),
        ).use { c -> if (c.moveToFirst()) CellTower(k, c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getInt(3)) else null }

    /** Update learned towers for every cell heard at a trusted position. */
    fun learn(keys: Collection<CellKey>, lat: Double, lon: Double, accuracyM: Double) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (k in keys) {
                val t = CellLearning.update(find(db, LEARNED, k), k, lat, lon, accuracyM)
                insert(db, LEARNED, t)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Import an OpenCellID CSV (plain or gzip). Replaces rows with the same key.
     * @return number of towers imported
     */
    fun importOpenCellId(input: InputStream, onProgress: (Long) -> Unit = {}): Long {
        val buffered = BufferedInputStream(input, 1 shl 16)
        buffered.mark(2)
        val magic = (buffered.read() shl 8) or buffered.read()
        buffered.reset()
        val stream = if (magic == 0x1f8b) GZIPInputStream(buffered, 1 shl 16) else buffered
        val db = writableDatabase
        val stmt = db.compileStatement("INSERT OR REPLACE INTO $IMPORTED (radio, mcc, mnc, area, cid, lat, lon, range, samples) VALUES (?,?,?,?,?,?,?,?,?)")
        var n = 0L
        db.beginTransaction()
        try {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = OpenCellIdCsv.parse(line) ?: continue
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
                    if (++n % 20_000 == 0L) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        onProgress(n)
                        db.beginTransaction()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(n)
        return n
    }

    fun clearImported() = writableDatabase.execSQL("DELETE FROM $IMPORTED")

    private fun insert(db: SQLiteDatabase, table: String, t: CellTower) {
        db.execSQL(
            "INSERT OR REPLACE INTO $table (radio, mcc, mnc, area, cid, lat, lon, range, samples) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(t.key.radio.ordinal, t.key.mcc, t.key.mnc, t.key.area, t.key.cid, t.lat, t.lon, t.rangeM, t.samples),
        )
    }

    private companion object {
        const val IMPORTED = "imported"
        const val LEARNED = "learned"
    }
}
