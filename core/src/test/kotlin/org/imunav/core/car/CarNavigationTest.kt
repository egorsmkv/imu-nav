package org.imunav.core.car

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNavigationTest {
    @Test fun navigationUrisAreValidatedBeforeEditing() {
        assertEquals(CarDestination.Point(GeoPoint(50.45, 30.52)), CarDestination.parse("geo:50.45,30.52"))
        assertEquals(CarDestination.Point(GeoPoint(50.45, 30.52)), CarDestination.parse("geo:0,0?q=50.45,30.52(Kyiv)"))
        assertEquals(CarDestination.Query("Kyiv Khreshchatyk 22"), CarDestination.parse("geo:0,0?q=Kyiv+Khreshchatyk+22"))
        listOf(null, "http://evil.example", "geo:NaN,30", "geo:91,30", "geo:50,181", "geo:0,0?q=%ZZ", "geo:0,0?q=%00", "geo:0,0?q=" + "x".repeat(300)).forEach {
            assertNull(it, CarDestination.parse(it))
        }
    }

    @Test fun consumersReleaseIndependentlyAndAnActiveTripKeepsSensing() {
        val ownership = DisplayOwnership()
        assertFalse(ownership.needsSensing(false))
        ownership.phone(true)
        ownership.car("car", true)
        ownership.phone(false)
        assertTrue(ownership.needsSensing(false))
        ownership.car("car", false)
        assertFalse(ownership.needsSensing(false))
        assertTrue(ownership.hasConsumer)
        ownership.disconnect("car")
        assertFalse(ownership.hasConsumer)
        assertTrue(ownership.needsSensing(true))
        ownership.car("new", true)
        ownership.disconnect("car")
        assertTrue(ownership.visible)
    }

    @Test fun demonstrationProgressesAndArrivesWithoutModifyingItsRoute() {
        val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.01, 30.0)), listOf(Step("depart"), Step("arrive", geometryIndex = 1)), 100.0)
        val demo = CarDemo(route, 1000)
        val start = demo.state(0)
        val middle = demo.state(51_000)
        val end = demo.state(200_000)
        assertEquals(0.0, start.s, 0.0)
        assertEquals(route.length / 2, middle.s, 0.01)
        assertEquals(route.length / 2, middle.remainingM, 0.01)
        assertEquals("arrive", middle.nextStep?.type)
        assertTrue(end.arrived)
        assertEquals(0f, end.speedKmh)
        assertEquals(0.0, end.remainingS, 0.0)
        assertEquals(GeoPoint(50.0, 30.0), route.geometry.first())
    }
}
