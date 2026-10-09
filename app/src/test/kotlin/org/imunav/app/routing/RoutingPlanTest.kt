package org.imunav.app.routing

import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingPlanTest {
    @Test
    fun walkingRequiresCoverageAndProfileAndNeverUsesOnlineRouting() {
        for (online in listOf(false, true)) {
            assertEquals(RoutingPlan.OFFLINE_ONLY, routingPlan(TravelMode.FOOT, covered = true, supported = true, onlineAllowed = online))
            assertEquals(RoutingPlan.WALKING_UNAVAILABLE, routingPlan(TravelMode.FOOT, covered = true, supported = false, onlineAllowed = online))
            assertEquals(RoutingPlan.WALKING_UNAVAILABLE, routingPlan(TravelMode.FOOT, covered = false, supported = true, onlineAllowed = online))
            assertEquals(RoutingPlan.WALKING_UNAVAILABLE, routingPlan(TravelMode.FOOT, covered = false, supported = false, onlineAllowed = online))
        }
    }

    @Test
    fun carTriesCoveredPackBeforeFallbackEvenWhenItsProfileIsMissing() {
        // Keep GraphHopper's existing failure/logging path instead of bypassing the offline attempt.
        for (supported in listOf(false, true)) {
            assertEquals(RoutingPlan.OFFLINE_ONLY, routingPlan(TravelMode.CAR, covered = true, supported = supported, onlineAllowed = false))
            assertEquals(RoutingPlan.OFFLINE_WITH_FALLBACK, routingPlan(TravelMode.CAR, covered = true, supported = supported, onlineAllowed = true))
        }
    }

    @Test
    fun carOutsideCoverageRespectsOnlineOptOut() {
        for (supported in listOf(false, true)) {
            assertEquals(RoutingPlan.OFFLINE_UNAVAILABLE, routingPlan(TravelMode.CAR, covered = false, supported = supported, onlineAllowed = false))
            assertEquals(RoutingPlan.ONLINE_ONLY, routingPlan(TravelMode.CAR, covered = false, supported = supported, onlineAllowed = true))
        }
    }
}
