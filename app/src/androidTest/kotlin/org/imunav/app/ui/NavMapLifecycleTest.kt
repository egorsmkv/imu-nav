package org.imunav.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.Style
import kotlin.math.abs

/** A local empty style exercises real MapLibre lifecycle without network tiles or private routes. */
@RunWith(AndroidJUnit4::class)
class NavMapLifecycleTest {
    @Test
    fun hiddenMapDefersCameraAndStyleAndResumesWithLatestValues() {
        val active = mutableStateOf(true)
        val point = mutableStateOf(GeoPoint(50.0, 30.0))
        val styleJson = mutableStateOf(style("first"))
        val controller = MapController()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    NavMap(
                        controller = controller,
                        dark = false,
                        route = null,
                        position = point.value,
                        accuracyM = 10.0,
                        bearingDeg = 0f,
                        destination = point.value,
                        following = true,
                        towers = null,
                        onLongPress = {},
                        onCenterChanged = {},
                        onViewport = { _, _, _, _, _ -> },
                        onUserPan = {},
                        modifier = Modifier.fillMaxSize(),
                        initialCenter = point.value,
                        animateCamera = false,
                        maxFps = 15,
                        prefetchZoomDelta = 0,
                        offlineStyleJson = styleJson.value,
                        active = active.value,
                    )
                }
            }
            await(scenario) { controller.map?.style?.getLayer("marker-dot") != null && abs(latitude(controller) - 50.0) < 0.0001 }
            var original: Style? = null
            scenario.onActivity {
                original = controller.map?.style
                active.value = false
            }
            Thread.sleep(300)
            scenario.onActivity {
                point.value = GeoPoint(51.0, 31.0)
                styleJson.value = style("second")
            }
            Thread.sleep(300)
            scenario.onActivity {
                assertSame(original, controller.map?.style)
                assertEquals(50.0, latitude(controller), 0.0001)
                point.value = GeoPoint(52.0, 32.0)
                styleJson.value = style("latest")
                active.value = true
            }
            await(scenario) {
                controller.map?.style !== original && controller.map?.style?.getLayer("marker-dot") != null && abs(latitude(controller) - 52.0) < 0.0001
            }
            scenario.onActivity { assertEquals(0, controller.map?.prefetchZoomDelta) }
            // Activity stop/resume must preserve the same map and reapply the latest position.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            point.value = GeoPoint(53.0, 33.0)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await(scenario) { abs(latitude(controller) - 53.0) < 0.0001 }
        }
    }

    private fun style(name: String) = """{"version":8,"name":"$name","sources":{},"layers":[]}"""
    private fun latitude(controller: MapController): Double = controller.map?.cameraPosition?.target?.latitude ?: Double.NaN

    private fun await(scenario: ActivityScenario<MainActivity>, ready: () -> Boolean) {
        repeat(150) {
            var done = false
            scenario.onActivity { done = ready() }
            if (done) return
            Thread.sleep(100)
        }
        error("Map did not reach the expected visible state")
    }
}
