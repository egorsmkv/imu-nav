package org.imunav.app.trips

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.record.RouteCodec
import org.imunav.core.route.Route
import org.json.JSONObject
import java.io.File

/** Fully decoded worker result; installing it never reads or decodes the saved route on main. */
data class RestoredTrip(
    val summary: TripSummary,
    val route: Route,
    val encodedRoute: String,
    val waypoints: List<GeoPoint>,
    val savedAt: Long,
    val startAccuracyM: Double,
    val progressM: Double,
    val uncertaintyM: Double,
    val estimator: NavigationEstimator,
) {
    /** Re-check at installation: preparation can wait while the app has no foreground host. */
    fun uncertaintyAt(nowWallMs: Long): Double? {
        val gapS = (nowWallMs - savedAt) / 1000.0
        return if (gapS in 0.0..MAX_GAP_S) (uncertaintyM + gapS * DRIFT_M_PER_S).coerceAtMost(3000.0) else null
    }

    companion object {
        private const val MAX_GAP_S = 3 * 3600.0
        private const val DRIFT_M_PER_S = 8.0
        private const val MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024L
        private const val ROUTE_ROUNDING_TOLERANCE_M = 10.0

        /** Caller owns the I/O thread; malformed snapshots never partially mutate the live engine. */
        fun read(file: File, nowWallMs: Long): RestoredTrip? = runCatching {
            if (!file.isFile || file.length() > MAX_SNAPSHOT_BYTES) return@runCatching null
            decode(JSONObject(file.readText()), nowWallMs)
        }.getOrNull()

        /** Decode every required field, safe file name and coordinate before offering a saved trip. */
        fun decode(document: JSONObject, nowWallMs: Long): RestoredTrip {
            val savedAt = document.getLong("savedAt")
            val summary = TripSummary.fromJson(JSONObject(document.toString()).put("end", savedAt))
            require(summary.id.matches(Regex("[A-Za-z0-9_-]+")))
            require(summary.recording == File(summary.recording).name && summary.recording.endsWith(".rec.gz"))
            require(summary.startWallMs in 0..savedAt)
            val destination = requireNotNull(summary.destination)
            require(validPoint(destination))
            val encoded = document.getString("route")
            val route = RouteCodec.decode(encoded)
            require(route.geometry.size >= 2 && route.geometry.all(::validPoint) && route.length.isFinite() && route.length > 0)
            val via = document.optJSONArray("via")
            require(via == null || via.length() % 2 == 0)
            val waypoints = if (via == null) emptyList() else (0 until via.length() step 2).map { GeoPoint(via.getDouble(it), via.getDouble(it + 1)) }
            require(waypoints.all(::validPoint))
            val progress = document.optDouble("s", 0.0)
            require(progress.isFinite() && progress in 0.0..(route.length + ROUTE_ROUNDING_TOLERANCE_M))
            val restored = RestoredTrip(
                summary, route, encoded, waypoints, savedAt,
                document.optDouble("startAcc", 0.0), progress.coerceAtMost(route.length), document.optDouble("unc", 100.0),
                NavigationEstimator.entries.firstOrNull { it.name == document.optString("estimator") } ?: NavigationEstimator.KOTLIN,
            )
            require(restored.startAccuracyM.isFinite() && restored.startAccuracyM >= 0)
            require(restored.uncertaintyM.isFinite() && restored.uncertaintyM >= 0)
            require(listOf(summary.drivenM, summary.movingS, summary.blindS, summary.blindM, summary.maxUncertaintyM).all { it.isFinite() && it >= 0 })
            require(summary.reroutes >= 0 && restored.uncertaintyAt(nowWallMs) != null)
            return restored
        }

        private fun validPoint(point: GeoPoint): Boolean = point.lat in -90.0..90.0 && point.lon in -180.0..180.0
    }
}
