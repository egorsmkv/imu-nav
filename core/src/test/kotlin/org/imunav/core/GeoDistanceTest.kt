package org.imunav.core

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.junit.Test
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rounding at antipodal coordinates must not turn a valid distance into NaN. */
class GeoDistanceTest {
    @Test
    fun antipodalDistancesRemainFiniteAndSymmetric() {
        for (latitude in -89..89) {
            val first = GeoPoint(latitude.toDouble(), -179.0)
            val second = GeoPoint(-latitude.toDouble(), 1.0)
            val distance = Geo.distance(first, second)
            assertTrue(distance.isFinite(), "latitude=$latitude")
            assertEquals(PI * 6_371_000.0, distance, 0.2)
            assertEquals(distance, Geo.distance(second, first), 1e-9)
        }
    }
}
