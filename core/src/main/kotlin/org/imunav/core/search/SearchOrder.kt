package org.imunav.core.search

import org.imunav.core.geo.GeoPoint
import kotlin.math.cos

/** Shared SQLite ordering keeps nearby matches in the candidate set before its limit on both platforms. */
data class SearchOrder(val sql: String, val arguments: List<String>) {
    companion object {
        /** Equirectangular distance is a candidate heuristic; final results use geodesic distance. Longitude wraps at the dateline. */
        fun near(alias: String, point: GeoPoint?): SearchOrder {
            require(alias == "p" || alias == "s")
            if (point == null || !point.lat.isFinite() || !point.lon.isFinite()) return SearchOrder(" ORDER BY $alias.id", emptyList())
            val longitudeWeight = cos(Math.toRadians(point.lat)).let { it * it }
            val longitude = "MIN(ABS($alias.lon - ?), 360 - ABS($alias.lon - ?))"
            return SearchOrder(
                " ORDER BY ($alias.lat - ?) * ($alias.lat - ?) + $longitude * $longitude * ?, $alias.id",
                listOf(point.lat, point.lat, point.lon, point.lon, point.lon, point.lon, longitudeWeight).map(Double::toString),
            )
        }
    }
}
