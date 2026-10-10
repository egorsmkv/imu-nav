package org.imunav.app.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.record.RouteCodec
import org.imunav.core.route.Route
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android JSON parsing, legacy snapshots and rejection before touching any engine. */
@RunWith(AndroidJUnit4::class)
class TripRestorationTest {
    @Test
    fun legacySnapshotDecodesGeometryAndKeepsMeasurementProgress() {
        val restored = RestoredTrip.decode(snapshot(), NOW)
        assertEquals(NavigationEstimator.KOTLIN, restored.estimator)
        assertEquals(20.0, restored.progressM, 0.0)
        assertEquals(2, restored.route.geometry.size)
        assertEquals(108.0, requireNotNull(restored.uncertaintyAt(NOW + 1000)), 0.0)
        assertNull(restored.uncertaintyAt(NOW + 3 * 3600_000L + 1))
    }

    @Test
    fun invalidStaleAndUnsafeSnapshotsAreRejectedAsAWhole() {
        val invalid = listOf(
            snapshot().put("recording", "../other.rec.gz"),
            snapshot().put("destLat", 91.0),
            snapshot().put("route", "broken"),
            snapshot().put("s", -1.0),
            snapshot().put("unc", -1.0),
            snapshot().put("savedAt", NOW - 3 * 3600_000L - 1),
            snapshot().put("savedAt", NOW + 1),
        )
        for (document in invalid) assertTrue(runCatching { RestoredTrip.decode(document, NOW) }.isFailure)
    }

    @Test
    fun arrivalProgressToleratesRouteCoordinateRounding() {
        val route = RestoredTrip.decode(snapshot(), NOW).route
        val restored = RestoredTrip.decode(snapshot().put("s", route.length + 0.5), NOW)
        assertEquals(route.length, restored.progressM, 0.0)
        assertTrue(runCatching { RestoredTrip.decode(snapshot().put("s", route.length + 100.0), NOW) }.isFailure)
    }

    private fun snapshot(): JSONObject {
        val route = Route(listOf(GeoPoint(50.45, 30.52), GeoPoint(50.451, 30.521)), emptyList(), 30.0)
        return JSONObject().put("id", "synthetic-trip").put("recording", "synthetic-trip.rec.gz")
            .put("start", NOW - 1000).put("savedAt", NOW).put("destLat", 50.451).put("destLon", 30.521)
            .put("route", RouteCodec.encode(route)).put("s", 20.0).put("unc", 100.0)
    }

    private companion object {
        const val NOW = 1_000_000_000L
    }
}
