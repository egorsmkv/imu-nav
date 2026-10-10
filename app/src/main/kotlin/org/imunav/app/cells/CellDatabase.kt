package org.imunav.app.cells

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.imunav.core.cells.CellCsv
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellLearning
import org.imunav.core.cells.CellSite
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.CellTowerDb
import org.imunav.core.cells.Radio
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt

/** Where a tower location came from. Lookup order = declaration order. */
enum class CellSource(val table: String, val label: String) {
    /** Merged community data from the configured sync server (freshest). */
    SHARED("shared", "sync server"),
    OPENCELLID("imported", "OpenCellID"),

    /** Located by this phone from trusted GPS. */
    LEARNED("learned", "learned"),

    /** Mozilla Location Service final export (public domain, March 2024). */
    MOZILLA("mozilla", "Mozilla"),

    /** Database shipped inside the APK (assets/cells/bundled-cells.csv.gz), imported after explicit opt-in. */
    BUNDLED("bundled", "built-in"),
}

/** Offline cell tower locations from several sources, one table each. */
class CellDatabase(context: Context) :
    SQLiteOpenHelper(context, "cells.db", null, 5),
    CellTowerDb {

    override fun onCreate(db: SQLiteDatabase) {
        for (s in CellSource.entries) createTable(db, s.table)
        db.execSQL("ALTER TABLE learned ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
        createIndexes(db)
    }

    /** (mcc, mnc, cid) lookups: cell id without area code, and LTE site (eNB) ranges. */
    private fun createIndexes(db: SQLiteDatabase) {
        for (s in CellSource.entries) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${s.table}_cid ON ${s.table} (mcc, mnc, cid)")
            // Map display: towers inside the visible bounding box.
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${s.table}_latlon ON ${s.table} (lat, lon)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTable(db, CellSource.SHARED.table)
            createTable(db, CellSource.MOZILLA.table)
            // Existing learned rows count as not yet uploaded.
            db.execSQL("ALTER TABLE learned ADD COLUMN updated INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 4) createTable(db, CellSource.BUNDLED.table)
        if (oldVersion < 5) createIndexes(db)
    }

    /** Every source has a table with the same columns; (radio, mcc, mnc, area, cid) is the key. */
    private fun createTable(db: SQLiteDatabase, table: String) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS $table (radio INTEGER NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, area INTEGER NOT NULL, " +
            "cid INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, range REAL NOT NULL, samples INTEGER NOT NULL, " +
            "PRIMARY KEY (mcc, mnc, area, cid, radio)) WITHOUT ROWID",
    )

    /** Number of towers per source. */
    fun counts(): Map<CellSource, Long> {
        val db = readableDatabase
        return CellSource.entries.associateWith { s ->
            db.rawQuery("SELECT COUNT(*) FROM ${s.table}", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }
    }

    /** How a cell was matched. */
    enum class Match(val symbol: String) { EXACT("✓"), CELL_ID("≈"), SITE("◌") }

    override fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower> = keys.mapNotNull { k -> resolve(k)?.let { k to it.first } }.toMap()

    /**
     * Find a tower for [k], trying progressively looser matches in every source:
     *  1. exact key;
     *  2. same operator + cell id, any area code (operators renumber TAC/LAC over time);
     *  3. LTE only: other sectors of the same site (eNB = ECI / 256) — same mast, so a robust
     *     median of their positions (outlier sectors dropped, see [CellSite]) stands in for it.
     */
    fun resolve(k: CellKey): Pair<CellTower, Match>? {
        val db = readableDatabase
        for (s in CellSource.entries) find(db, s.table, k)?.let { return it to Match.EXACT }
        for (s in CellSource.entries) {
            db.rawQuery(
                "SELECT lat, lon, range, samples FROM ${s.table} WHERE mcc=? AND mnc=? AND cid=? AND radio=? ORDER BY samples DESC LIMIT 1",
                arrayOf(k.mcc.toString(), k.mnc.toString(), k.cid.toString(), k.radio.ordinal.toString()),
            ).use { c -> if (c.moveToFirst()) return CellTower(k, c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getInt(3)) to Match.CELL_ID }
        }
        if (k.radio == Radio.LTE) {
            val site = k.cid / 256
            for (s in CellSource.entries) {
                val sectors = ArrayList<CellTower>()
                db.rawQuery(
                    "SELECT lat, lon, range, samples FROM ${s.table} WHERE mcc=? AND mnc=? AND cid BETWEEN ? AND ? AND radio=?",
                    arrayOf(k.mcc.toString(), k.mnc.toString(), (site * 256).toString(), (site * 256 + 255).toString(), k.radio.ordinal.toString()),
                ).use { c -> while (c.moveToNext()) sectors += CellTower(k, c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getInt(3)) }
                CellSite.combine(k, sectors)?.let { return it to Match.SITE }
            }
        }
        return null
    }

    /** The tower for exactly [k] in [table], or null. */
    private fun find(db: SQLiteDatabase, table: String, k: CellKey): CellTower? = db.rawQuery(
        "SELECT lat, lon, range, samples FROM $table WHERE mcc=? AND mnc=? AND area=? AND cid=? AND radio=?",
        arrayOf(k.mcc.toString(), k.mnc.toString(), k.area.toString(), k.cid.toString(), k.radio.ordinal.toString()),
    ).use { c -> if (c.moveToFirst()) CellTower(k, c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getInt(3)) else null }

    /** Update learned towers for every cell heard at a trusted position. */
    fun learn(keys: Collection<CellKey>, lat: Double, lon: Double, accuracyM: Double, nowMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.transaction {
            for (k in keys) {
                val t = CellLearning.update(find(db, CellSource.LEARNED.table, k), k, lat, lon, accuracyM)
                db.execSQL(
                    "INSERT OR REPLACE INTO learned (radio, mcc, mnc, area, cid, lat, lon, range, samples, updated) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(t.key.radio.ordinal, t.key.mcc, t.key.mnc, t.key.area, t.key.cid, t.lat, t.lon, t.rangeM, t.samples, nowMs),
                )
            }
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
    fun importStream(source: CellSource, input: InputStream, mccs: Set<Int>? = null, onProgress: (read: Long, kept: Long) -> Boolean = { _, _ -> true }): Long {
        val db = writableDatabase
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO ${source.table} (radio, mcc, mnc, area, cid, lat, lon, range, samples) VALUES (?,?,?,?,?,?,?,?,?)",
        )
        var read = 0L
        var kept = 0L
        db.transaction {
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
                    // Commit in chunks. Open the next transaction before a possible cancel so the
                    // enclosing transaction {} always has one to end.
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                    if (!onProgress(read, kept)) throw InterruptedException("cancelled")
                }
            }
        }
        onProgress(read, kept)
        return kept
    }

    /** Insert or replace [towers] in [source]'s table (one transaction). */
    fun upsert(source: CellSource, towers: List<CellTower>) {
        if (towers.isEmpty()) return
        val db = writableDatabase
        db.transaction {
            for (tower in towers) {
                val key = tower.key
                db.execSQL(
                    "INSERT OR REPLACE INTO ${source.table} (radio, mcc, mnc, area, cid, lat, lon, range, samples) VALUES (?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(key.radio.ordinal, key.mcc, key.mnc, key.area, key.cid, tower.lat, tower.lon, tower.rangeM, tower.samples),
                )
            }
        }
    }

    /** Apply one completed sync atomically, so a failed download never advances local tower state. */
    fun applySharedSync(removals: Collection<CellKey>, towers: Collection<CellTower>, replace: Boolean = false) {
        val db = writableDatabase
        db.transaction {
            if (replace) db.execSQL("DELETE FROM shared")
            for (key in removals) {
                db.execSQL(
                    "DELETE FROM shared WHERE radio=? AND mcc=? AND mnc=? AND area=? AND cid=?",
                    arrayOf<Any>(key.radio.ordinal, key.mcc, key.mnc, key.area, key.cid),
                )
            }
            for (tower in towers) {
                val key = tower.key
                db.execSQL(
                    "INSERT OR REPLACE INTO shared (radio,mcc,mnc,area,cid,lat,lon,range,samples) VALUES (?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(key.radio.ordinal, key.mcc, key.mnc, key.area, key.cid, tower.lat, tower.lon, tower.rangeM, tower.samples),
                )
            }
        }
    }

    /**
     * Write every known tower once, choosing per cell the source that lookups would use
     * (declaration order of [CellSource]), as gzip CSV in OpenCellID columns.
     * @return towers written
     */
    fun exportMerged(out: OutputStream, sources: List<CellSource> = CellSource.entries, onProgress: (Long) -> Unit = {}): Long {
        val db = readableDatabase
        var n = 0L
        GZIPOutputStream(out, 1 shl 16).bufferedWriter().use { w ->
            w.write(CellCsv.HEADER)
            w.write("\n")
            for ((i, s) in sources.withIndex()) {
                val higher = sources.take(i)
                val notInHigher = higher.joinToString("") { h ->
                    " AND NOT EXISTS (SELECT 1 FROM ${h.table} h WHERE h.mcc=t.mcc AND h.mnc=t.mnc AND h.area=t.area AND h.cid=t.cid AND h.radio=t.radio)"
                }
                db.rawQuery("SELECT radio, mcc, mnc, area, cid, lat, lon, range, samples FROM ${s.table} t WHERE 1=1$notInHigher", null).use { c ->
                    while (c.moveToNext()) {
                        val key = CellKey(Radio.entries[c.getInt(0)], c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4))
                        w.write(CellCsv.format(CellTower(key, c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getInt(8))))
                        w.write("\n")
                        if (++n % 50_000 == 0L) onProgress(n)
                    }
                }
            }
        }
        onProgress(n)
        return n
    }

    /**
     * Towers inside a bounding box, one per cell (highest-priority source wins).
     * When the box holds more than [limit] towers, they are thinned on a grid (one tower per grid
     * square) so the sample covers the whole view evenly instead of one band of the index order.
     * @return towers with their source, and whether the result was thinned
     */
    fun towersIn(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int,
        radios: Set<Radio> = Radio.entries.toSet(),
    ): Pair<List<Pair<CellTower, CellSource>>, Boolean> {
        if (radios.isEmpty()) return emptyList<Pair<CellTower, CellSource>>() to false
        val db = readableDatabase
        val box = arrayOf(south.toString(), north.toString(), west.toString(), east.toString())
        val where = "lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND radio IN (${radios.joinToString(",") { it.ordinal.toString() }})"
        val total = CellSource.entries.sumOf { s ->
            db.rawQuery("SELECT COUNT(*) FROM ${s.table} WHERE $where", box).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }
        val thinned = total > limit
        val grid = sqrt(limit.toDouble()).toInt().coerceAtLeast(1)
        val dLat = ((north - south) / grid).coerceAtLeast(1e-9)
        val dLon = ((east - west) / grid).coerceAtLeast(1e-9)
        val seen = LinkedHashMap<CellKey, Pair<CellTower, CellSource>>()
        val occupied = HashSet<Long>()
        for (s in CellSource.entries) {
            val sql = if (thinned) {
                // One row per grid square (SQLite returns the row that holds MIN()).
                "SELECT radio, mcc, mnc, area, cid, lat, lon, range, MIN(samples) FROM ${s.table} WHERE $where " +
                    "GROUP BY CAST((lat - $south) / $dLat AS INTEGER), CAST((lon - $west) / $dLon AS INTEGER)"
            } else {
                "SELECT radio, mcc, mnc, area, cid, lat, lon, range, samples FROM ${s.table} WHERE $where"
            }
            db.rawQuery(sql, box).use { c ->
                while (c.moveToNext()) {
                    val key = CellKey(Radio.entries[c.getInt(0)], c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4))
                    if (key in seen) continue
                    val lat = c.getDouble(5)
                    val lon = c.getDouble(6)
                    if (thinned) {
                        val cell = ((lat - south) / dLat).toLong() * 100_000 + ((lon - west) / dLon).toLong()
                        if (!occupied.add(cell)) continue
                    }
                    seen[key] = CellTower(key, lat, lon, c.getDouble(7), c.getInt(8)) to s
                }
            }
        }
        return seen.values.take(limit) to thinned
    }

    /** Delete all towers of one source. */
    fun clear(source: CellSource) = writableDatabase.execSQL("DELETE FROM ${source.table}")

    /** Rebuild the file to give space freed by deletes back to the system. */
    fun vacuum() = writableDatabase.execSQL("VACUUM")
}
