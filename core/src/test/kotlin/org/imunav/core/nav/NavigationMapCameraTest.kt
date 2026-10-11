package org.imunav.core.nav

import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers speed extremes, travel-mode transitions and independent control visibility. */
class NavigationMapCameraTest {
    @Test
    fun fasterDrivingShowsMoreMapWithoutSpeedBandJumps() {
        val zooms = (0..200).map { NavigationMapCamera.zoom(TravelMode.CAR, it.toFloat(), automatic = true) }
        assertEquals(17.0, zooms.first(), 0.0)
        assertEquals(14.5, zooms.last(), 0.0)
        zooms.zipWithNext().forEach { (previous, next) ->
            assertTrue(next <= previous)
            assertTrue(previous - next < 0.03)
        }
        assertEquals(15.75, zooms[70], 0.0001)
    }

    @Test
    fun invalidSpeedCannotCreateAnInvalidCamera() {
        listOf(-100f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(17.0, NavigationMapCamera.zoom(TravelMode.CAR, it, automatic = true), 0.0)
        }
        assertEquals(14.5, NavigationMapCamera.zoom(TravelMode.CAR, Float.MAX_VALUE, automatic = true), 0.0)
    }

    @Test
    fun walkingAndDisabledAutoZoomKeepTheirFixedScales() {
        listOf(0f, 50f, 130f, Float.NaN).forEach {
            assertEquals(17.5, NavigationMapCamera.zoom(TravelMode.FOOT, it, automatic = true), 0.0)
            assertEquals(17.5, NavigationMapCamera.zoom(TravelMode.FOOT, it, automatic = false), 0.0)
            assertEquals(16.0, NavigationMapCamera.zoom(TravelMode.CAR, it, automatic = false), 0.0)
        }
    }

    @Test
    fun perspectiveRequiresAnActiveCarTripAndEnabledSetting() {
        TravelMode.entries.forEach { mode ->
            listOf(false, true).forEach { active ->
                listOf(false, true).forEach { enabled ->
                    val expected = if (mode == TravelMode.CAR && active && enabled) 45.0 else 0.0
                    assertEquals(expected, NavigationMapCamera.tilt(mode, active, enabled), 0.0)
                }
            }
        }
    }

    @Test
    fun mapBrowsingAlwaysRestoresIndependentlyHiddenControls() {
        listOf(false, true).forEach { hideTowers ->
            listOf(false, true).forEach { hideZoom ->
                val options = MapDisplayOptions(hideTowerButton = hideTowers, hideZoomButtons = hideZoom)
                assertTrue(options.showTowerButton(navigating = false))
                assertTrue(options.showZoomButtons(navigating = false))
                assertEquals(!hideTowers, options.showTowerButton(navigating = true))
                assertEquals(!hideZoom, options.showZoomButtons(navigating = true))
            }
        }
    }
}
