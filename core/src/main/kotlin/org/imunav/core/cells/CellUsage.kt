package org.imunav.core.cells

import java.io.Writer

/** A timestamped positioning contribution; session ids distinguish elapsed times across app restarts. */
data class CellUsageRecord(
    val sessionId: String,
    val timeMs: Long,
    val elapsedMs: Long,
    val contribution: CellContribution,
    val fixLat: Double,
    val fixLon: Double,
    val accuracyM: Double,
)

/** Locale-independent, streaming CSV for the usage timeline (not an importable tower database). */
object CellUsageCsv {
    const val HEADER = "session_id,time_unix_ms,elapsed_ms,radio,mcc,mnc,area,cell_id,signal_dbm,serving,timing_advance," +
        "tower_lat,tower_lon,tower_range_m,fix_lat,fix_lon,fix_accuracy_m"

    /** Numeric fields use decimal dots; quote the session field so even externally supplied ids remain valid CSV. */
    fun writeRecord(writer: Writer, record: CellUsageRecord) {
        val observation = record.contribution.observation
        val tower = record.contribution.tower
        val key = observation.key
        val fields = listOf(
            "\"${record.sessionId.replace("\"", "\"\"")}\"", record.timeMs, record.elapsedMs, key.radio.name,
            key.mcc, key.mnc, key.area, key.cid, observation.dbm ?: "", if (observation.serving) 1 else 0,
            observation.timingAdvance ?: "", tower.lat, tower.lon, tower.rangeM, record.fixLat, record.fixLon, record.accuracyM,
        )
        writer.append(fields.joinToString(",")).append('\n')
    }
}
