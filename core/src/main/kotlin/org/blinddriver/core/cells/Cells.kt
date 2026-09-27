package org.blinddriver.core.cells

import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.LocalProjection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Radio technology, matching OpenCellID's `radio` column. */
enum class Radio { GSM, UMTS, LTE, NR, CDMA }

/**
 * Globally unique cell identity. [area] is LAC (GSM/UMTS) or TAC (LTE/NR); [cid] is the full cell id
 * (28-bit UCID for UMTS, 28-bit ECI for LTE, 36-bit NCI for NR) — the same numbering OpenCellID uses.
 */
data class CellKey(val radio: Radio, val mcc: Int, val mnc: Int, val area: Int, val cid: Long)

/** Known location of a cell's antenna (really: centroid of where it was heard). */
data class CellTower(
    val key: CellKey,
    val lat: Double,
    val lon: Double,
    /** Approximate coverage radius, metres. */
    val rangeM: Double,
    val samples: Int,
)

/** One cell currently seen by the modem. */
data class CellObservation(
    val key: CellKey,
    /** Signal strength, dBm (RSRP for LTE/NR). Null if unknown. */
    val dbm: Int? = null,
    /** True for the serving (registered) cell. */
    val serving: Boolean = false,
    /** LTE timing advance (units of ~78 m round trip), if reported. */
    val timingAdvance: Int? = null,
)

/** A position estimate computed from cell towers. */
data class CellFix(val lat: Double, val lon: Double, val accuracyM: Double, val towersUsed: Int, val towersSeen: Int)

interface CellTowerDb {
    fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower>
}

class InMemoryCellTowerDb(towers: Collection<CellTower> = emptyList()) : CellTowerDb {
    private val map = towers.associateBy { it.key }.toMutableMap()
    fun put(t: CellTower) {
        map[t.key] = t
    }
    override fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower> = keys.mapNotNull { k -> map[k]?.let { k to it } }.toMap()
}

/**
 * Offline position from visible cell towers: weighted centroid of the known tower locations.
 *
 * Weights favour the serving cell, strong signals and small cells (small range ⇒ the phone must be
 * close). Towers farther than 25 km from the median are treated as database errors and dropped.
 * With LTE timing advance on the serving cell, its distance bounds the accuracy.
 */
object CellPositioner {
    private const val OUTLIER_M = 25_000.0
    private const val MIN_ACCURACY_M = 150.0
    private const val MAX_ACCURACY_M = 5_000.0
    private const val TA_METERS = 78.12

    fun locate(observations: List<CellObservation>, db: CellTowerDb): CellFix? {
        if (observations.isEmpty()) return null
        val towers = db.lookup(observations.map { it.key })
        var located = observations.mapNotNull { o -> towers[o.key]?.let { o to it } }
        if (located.isEmpty()) return null

        if (located.size >= 3) {
            val medLat = located.map { it.second.lat }.sorted()[located.size / 2]
            val medLon = located.map { it.second.lon }.sorted()[located.size / 2]
            val kept = located.filter { Geo.distance(medLat, medLon, it.second.lat, it.second.lon) <= OUTLIER_M }
            if (kept.isNotEmpty()) located = kept
        }

        val proj = LocalProjection(GeoPoint(located[0].second.lat, located[0].second.lon))
        var wSum = 0.0
        var x = 0.0
        var y = 0.0
        val weights = located.map { (o, t) -> weight(o, t) }
        for ((i, pair) in located.withIndex()) {
            val t = pair.second
            val w = weights[i]
            wSum += w
            x += w * proj.x(GeoPoint(t.lat, t.lon))
            y += w * proj.y(GeoPoint(t.lat, t.lon))
        }
        x /= wSum
        y /= wSum
        val center = proj.toGeo(x, y)

        // Accuracy: typical coverage radius, shrunk by geometry (more towers spread around ⇒ better).
        val meanRange = located.indices.sumOf { weights[it] * range(located[it].second) } / wSum
        var spread = 0.0
        for ((i, pair) in located.withIndex()) {
            val t = pair.second
            val dx = proj.x(GeoPoint(t.lat, t.lon)) - x
            val dy = proj.y(GeoPoint(t.lat, t.lon)) - y
            spread += weights[i] * (dx * dx + dy * dy)
        }
        spread = sqrt(spread / wSum)
        var accuracy = if (located.size == 1) meanRange else max(spread, meanRange / sqrt(located.size.toDouble()))

        val serving = located.firstOrNull { it.first.serving && it.first.timingAdvance != null }
        if (serving != null) {
            val taDist = (serving.first.timingAdvance!! + 1) * TA_METERS
            if (located.size == 1) accuracy = min(accuracy, taDist)
        }
        accuracy = accuracy.coerceIn(MIN_ACCURACY_M, MAX_ACCURACY_M)
        return CellFix(center.lat, center.lon, accuracy, located.size, observations.size)
    }

    private fun range(t: CellTower): Double = t.rangeM.coerceIn(200.0, 10_000.0)

    private fun weight(o: CellObservation, t: CellTower): Double {
        val signal = o.dbm?.let { 10.0.pow(((it + 140).coerceIn(1, 100)) / 20.0) } ?: 10.0.pow(2.0)
        val servingBoost = if (o.serving) 2.0 else 1.0
        return servingBoost * signal / range(t)
    }
}

/**
 * Learns where cells are from trusted (GOOD) GPS fixes: running centroid of positions where each
 * cell was heard, with range = max distance from the centroid seen so far.
 */
object CellLearning {
    fun update(existing: CellTower?, key: CellKey, lat: Double, lon: Double, accuracyM: Double): CellTower {
        if (existing == null) return CellTower(key, lat, lon, max(accuracyM, 100.0), 1)
        val n = existing.samples
        val newLat = existing.lat + (lat - existing.lat) / (n + 1)
        val newLon = existing.lon + (lon - existing.lon) / (n + 1)
        val dist = Geo.distance(newLat, newLon, lat, lon)
        return CellTower(key, newLat, newLon, max(existing.rangeM, dist + accuracyM), min(n + 1, 10_000))
    }
}

/**
 * Parser for OpenCellID CSV exports:
 * `radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal`
 */
object OpenCellIdCsv {
    fun parse(line: String): CellTower? {
        if (line.isEmpty() || line.startsWith("radio")) return null
        val f = line.split(',')
        if (f.size < 10) return null
        val radio = when (f[0].trim().uppercase()) {
            "GSM" -> Radio.GSM
            "UMTS" -> Radio.UMTS
            "LTE" -> Radio.LTE
            "NR" -> Radio.NR
            "CDMA" -> Radio.CDMA
            else -> return null
        }
        val mcc = f[1].toIntOrNull() ?: return null
        val mnc = f[2].toIntOrNull() ?: return null
        val area = f[3].toIntOrNull() ?: return null
        val cid = f[4].toLongOrNull() ?: return null
        val lon = f[6].toDoubleOrNull() ?: return null
        val lat = f[7].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        val range = f[8].toDoubleOrNull() ?: 1000.0
        val samples = f[9].toIntOrNull() ?: 1
        return CellTower(CellKey(radio, mcc, mnc, area, cid), lat, lon, range, samples)
    }
}

/**
 * Estimates a site (mast) position from the known positions of its sectors — used when the exact
 * cell is unknown but sibling sectors of the same LTE eNB are. Crowd-sourced sector positions are
 * noisy, so the estimate is robust:
 *  - centre = component-wise median;
 *  - sectors farther than max(3 × MAD, [MIN_OUTLIER_M]) from it are dropped (MAD = median distance);
 *  - with exactly two sectors disagreeing by more than [PAIR_CONFLICT_M], the better-sampled one wins;
 *  - range = max(median sector range, farthest kept sector from the centre).
 */
object CellSite {
    const val MIN_OUTLIER_M = 500.0
    const val PAIR_CONFLICT_M = 5_000.0

    fun combine(key: CellKey, sectors: List<CellTower>): CellTower? {
        if (sectors.isEmpty()) return null
        if (sectors.size == 1) return sectors[0].copy(key = key)
        if (sectors.size == 2) {
            val (a, b) = sectors
            if (Geo.distance(a.lat, a.lon, b.lat, b.lon) > PAIR_CONFLICT_M) {
                return (if (a.samples >= b.samples) a else b).copy(key = key)
            }
        }
        var kept = sectors
        val lat0 = median(sectors.map { it.lat })
        val lon0 = median(sectors.map { it.lon })
        if (sectors.size >= 3) {
            val dist = sectors.map { Geo.distance(lat0, lon0, it.lat, it.lon) }
            val limit = max(3.0 * median(dist), MIN_OUTLIER_M)
            kept = sectors.filterIndexed { i, _ -> dist[i] <= limit }.ifEmpty { sectors }
        }
        val lat = median(kept.map { it.lat })
        val lon = median(kept.map { it.lon })
        val farthest = kept.maxOf { Geo.distance(lat, lon, it.lat, it.lon) }
        val range = max(median(kept.map { it.rangeM }), farthest)
        return CellTower(key, lat, lon, range, kept.sumOf { it.samples })
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
