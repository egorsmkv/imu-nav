package org.imunav.app.car

import org.imunav.app.UiState
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CarDisplayContentTest {
    @Test
    fun logsAndMovingMapMarkerDoNotInvalidateHostTemplates() {
        val before = UiState()
        val after = before.copy(log = listOf("new log"), currentPosition = GeoPoint(50.0, 30.0))
        val trip = carTripContent(before, "ready", 1000)
        val updated = carTripContent(after, "ready", 1000)
        assertEquals(trip, updated)
        assertEquals(carTemplateContent(before, trip, TravelMode.CAR), carTemplateContent(after, updated, TravelMode.CAR))
    }

    @Test
    fun distanceReroutingArrivalAndStationaryEtaChangesReachTheHost() {
        val ui = UiState()
        val original = carTripContent(ui, "ready", 1000)
        for (guidance in listOf(ui.guidance.copy(remainingM = 20.0), ui.guidance.copy(rerouting = true), ui.guidance.copy(arrived = true))) {
            assertNotEquals(original, carTripContent(ui.copy(guidance = guidance), "ready", 1000))
        }
        assertNotEquals(original, carTripContent(ui, "ready", 61_000))
        assertNotEquals(
            carTemplateContent(ui, original, TravelMode.CAR),
            carTemplateContent(ui.copy(planning = true), original, TravelMode.CAR),
        )
    }
}
