package org.imunav.core.cells

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.LocalProjection
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

/** Somewhere tower positions can be looked up (SQLite on the phone, a map in tests). */
interface CellTowerDb {
    /** The known towers among [keys]; unknown keys are simply missing from the result. */
    fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower>
}

/** A [CellTowerDb] kept in memory — handy for tests. */
class InMemoryCellTowerDb(towers: Collection<CellTower> = emptyList()) : CellTowerDb {
    private val towersByKey = towers.associateBy { it.key }.toMutableMap()

    fun put(tower: CellTower) {
        towersByKey[tower.key] = tower
    }

    override fun lookup(keys: Collection<CellKey>): Map<CellKey, CellTower> = keys.mapNotNull { key -> towersByKey[key]?.let { key to it } }.toMap()
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

    /** A seen cell whose tower position we know, with its flat x/y (metres) and its weight. */
    private class Located(val observation: CellObservation, val tower: CellTower, val x: Double, val y: Double, val weight: Double)

    /** Position from the cells the modem sees now, or null if none of them is in [db]. */
    fun locate(observations: List<CellObservation>, db: CellTowerDb): CellFix? {
        if (observations.isEmpty()) return null
        val towersByKey = db.lookup(observations.map { it.key })
        var known = observations.mapNotNull { obs -> towersByKey[obs.key]?.let { obs to it } }
        if (known.isEmpty()) return null
        if (known.size >= 3) known = dropFarTowers(known)

        // Flat x/y metres around the first tower, so averaging positions is plain arithmetic.
        val flat = LocalProjection(GeoPoint(known[0].second.lat, known[0].second.lon))
        val located = known.map { (obs, tower) ->
            val towerPoint = GeoPoint(tower.lat, tower.lon)
            Located(obs, tower, flat.x(towerPoint), flat.y(towerPoint), weight(obs, tower))
        }

        // Weighted centroid = the estimated position.
        val weightSum = located.sumOf { it.weight }
        val centerX = located.sumOf { it.weight * it.x } / weightSum
        val centerY = located.sumOf { it.weight * it.y } / weightSum
        val center = flat.toGeo(centerX, centerY)

        return CellFix(center.lat, center.lon, accuracy(located, centerX, centerY, weightSum), located.size, observations.size)
    }

    /**
     * Towers farther than [OUTLIER_M] from the median position are almost certainly wrong in the
     * database (a moved or mis-entered cell); ignore them unless that would drop everything.
     */
    private fun dropFarTowers(known: List<Pair<CellObservation, CellTower>>): List<Pair<CellObservation, CellTower>> {
        val medianLat = known.map { it.second.lat }.sorted()[known.size / 2]
        val medianLon = known.map { it.second.lon }.sorted()[known.size / 2]
        val kept = known.filter { (_, tower) -> Geo.distance(medianLat, medianLon, tower.lat, tower.lon) <= OUTLIER_M }
        return kept.ifEmpty { known }
    }

    /**
     * Rough accuracy radius: the typical coverage radius of the towers, improved by geometry (more
     * towers spread around us ⇒ better). One LTE serving cell with timing advance cannot be
     * farther than the distance the timing advance says.
     */
    private fun accuracy(located: List<Located>, centerX: Double, centerY: Double, weightSum: Double): Double {
        val meanRange = located.sumOf { it.weight * range(it.tower) } / weightSum
        val spread = sqrt(
            located.sumOf { tower ->
                val dx = tower.x - centerX
                val dy = tower.y - centerY
                tower.weight * (dx * dx + dy * dy)
            } / weightSum,
        )
        var accuracy = if (located.size == 1) meanRange else max(spread, meanRange / sqrt(located.size.toDouble()))
        val timingAdvance = located.firstOrNull { it.observation.serving && it.observation.timingAdvance != null }?.observation?.timingAdvance
        if (timingAdvance != null && located.size == 1) accuracy = min(accuracy, (timingAdvance + 1) * TA_METERS)
        return accuracy.coerceIn(MIN_ACCURACY_M, MAX_ACCURACY_M)
    }

    /** Tower coverage radius, limited to sane values (databases contain 0 m and 100 km). */
    private fun range(tower: CellTower): Double = tower.rangeM.coerceIn(200.0, 10_000.0)

    /**
     * How much a tower counts: stronger signal (converted from dBm to a linear scale) and a
     * smaller cell both mean the phone is close to it; the serving cell counts double.
     */
    private fun weight(observation: CellObservation, tower: CellTower): Double {
        val signal = observation.dbm?.let { dbm -> 10.0.pow(((dbm + 140).coerceIn(1, 100)) / 20.0) } ?: 10.0.pow(2.0)
        val servingBoost = if (observation.serving) 2.0 else 1.0
        return servingBoost * signal / range(tower)
    }
}

/**
 * Learns where cells are from trusted (GOOD) GPS fixes: running centroid of positions where each
 * cell was heard, with range = max distance from the centroid seen so far.
 */
object CellLearning {
    /** Add one sighting of [key] at a trusted GPS position; [existing] is what we knew before (or null). */
    fun update(existing: CellTower?, key: CellKey, lat: Double, lon: Double, accuracyM: Double): CellTower {
        if (existing == null) return CellTower(key, lat, lon, max(accuracyM, 100.0), 1)
        val count = existing.samples
        // Running average: move the old centre 1/(n+1) of the way towards the new sighting.
        val newLat = existing.lat + (lat - existing.lat) / (count + 1)
        val newLon = existing.lon + (lon - existing.lon) / (count + 1)
        val distance = Geo.distance(newLat, newLon, lat, lon)
        return CellTower(key, newLat, newLon, max(existing.rangeM, distance + accuracyM), min(count + 1, 10_000))
    }
}

/**
 * Parser for OpenCellID CSV exports:
 * `radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal`
 */
object OpenCellIdCsv {
    // Column positions in the CSV.
    private const val RADIO = 0
    private const val MCC = 1
    private const val MNC = 2
    private const val AREA = 3
    private const val CELL = 4
    private const val LON = 6
    private const val LAT = 7
    private const val RANGE = 8
    private const val SAMPLES = 9

    /** One CSV line → a tower, or null for the header and any invalid line. */
    fun parse(line: String): CellTower? {
        if (line.isEmpty() || line.startsWith("radio")) return null
        val fields = line.split(',')
        if (fields.size <= SAMPLES) return null
        val radio = Radio.entries.firstOrNull { it.name == fields[RADIO].trim().uppercase() } ?: return null
        val mcc = fields[MCC].toIntOrNull() ?: return null
        val mnc = fields[MNC].toIntOrNull() ?: return null
        val area = fields[AREA].toIntOrNull() ?: return null
        val cid = fields[CELL].toLongOrNull() ?: return null
        val lon = fields[LON].toDoubleOrNull() ?: return null
        val lat = fields[LAT].toDoubleOrNull() ?: return null
        // 0,0 ("Null Island") is a common placeholder for "unknown" in crowd-sourced data.
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        val range = fields[RANGE].toDoubleOrNull() ?: 1000.0
        val samples = fields[SAMPLES].toIntOrNull() ?: 1
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

    /** The middle value (average of the two middle values for an even count). */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
