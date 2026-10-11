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
import org.maplibre.android.style.layers.Property
import kotlin.math.abs

/** A local empty style exercises real MapLibre lifecycle without network tiles or private routes. */
@RunWith(AndroidJUnit4::class)
class NavMapLifecycleTest {
    @Test
    fun hiddenMapDefersCameraAndStyleAndResumesWithLatestValues() {
        val active = mutableStateOf(true)
        val following = mutableStateOf(true)
        val topInset = mutableStateOf(0)
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
                        following = following.value,
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
                        insetTopPx = topInset.value,
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

            // Selecting an address must survive the search overlay closing and later GPS updates.
            scenario.onActivity {
                following.value = false
                controller.moveTo(GeoPoint(50.45, 30.52), 17.0, animate = false)
                topInset.value = 120
                point.value = GeoPoint(54.0, 34.0)
            }
            Thread.sleep(500)
            scenario.onActivity {
                assertEquals(50.45, latitude(controller), 0.0001)
                assertEquals(30.52, controller.map?.cameraPosition?.target?.longitude ?: Double.NaN, 0.0001)
                assertEquals(17.0, controller.map?.cameraPosition?.zoom ?: Double.NaN, 0.0001)
            }
        }
    }

    @Test
    fun presentationChangesSurviveStyleReloadAndManualZoomCanResumeAutomaticZoom() {
        val buildings = mutableStateOf(false)
        val tilt = mutableStateOf(45.0)
        val zoom = mutableStateOf(17.0)
        val following = mutableStateOf(true)
        val styleJson = mutableStateOf(buildingStyle("first"))
        val controller = MapController()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    NavMap(
                        controller = controller,
                        dark = false,
                        route = null,
                        position = GeoPoint(50.0, 30.0),
                        accuracyM = null,
                        bearingDeg = 90f,
                        destination = null,
                        following = following.value,
                        towers = null,
                        onLongPress = {},
                        onCenterChanged = {},
                        onViewport = { _, _, _, _, _ -> },
                        onUserPan = {},
                        modifier = Modifier.fillMaxSize(),
                        animateCamera = false,
                        offlineStyleJson = styleJson.value,
                        buildings3d = buildings.value,
                        cameraTilt = tilt.value,
                        followZoomDefault = zoom.value,
                    )
                }
            }
            await(scenario) {
                visibility(controller, "buildings") == Property.NONE &&
                    abs((controller.map?.cameraPosition?.tilt ?: 0.0) - 45.0) < 0.01
            }
            // A changed speed affects zoom even when the position is unchanged.
            scenario.onActivity { zoom.value = 14.5 }
            await(scenario) { abs((controller.map?.cameraPosition?.zoom ?: 0.0) - 14.5) < 0.01 }
            scenario.onActivity { controller.followZoom = 16.0 }
            await(scenario) { abs((controller.map?.cameraPosition?.zoom ?: 0.0) - 16.0) < 0.01 }
            scenario.onActivity {
                zoom.value = 15.0
                buildings.value = true
            }
            await(scenario) { visibility(controller, "buildings") == Property.VISIBLE }
            scenario.onActivity {
                assertEquals(16.0, controller.map?.cameraPosition?.zoom ?: 0.0, 0.01)
                assertEquals(Property.NONE, visibility(controller, "hidden-buildings"))
                assertEquals(Property.VISIBLE, visibility(controller, "footprints"))
                controller.followZoom = null
            }
            await(scenario) { abs((controller.map?.cameraPosition?.zoom ?: 0.0) - 15.0) < 0.01 }
            scenario.onActivity {
                buildings.value = false
                styleJson.value = buildingStyle("replacement")
            }
            await(scenario) {
                controller.map?.style?.getLayer("replacement") != null && visibility(controller, "buildings") == Property.NONE
            }
            // Ending navigation must flatten the camera even after panning stopped following.
            scenario.onActivity {
                following.value = false
                tilt.value = 0.0
            }
            await(scenario) { abs(controller.map?.cameraPosition?.tilt ?: Double.NaN) < 0.01 }
        }
    }

    private fun visibility(controller: MapController, layer: String) = controller.map?.style?.getLayer(layer)?.visibility?.value

    private fun buildingStyle(name: String) = """
        {
          "version": 8,
          "sources": {"test": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}}},
          "layers": [
            {"id": "$name", "type": "background"},
            {"id": "footprints", "type": "fill", "source": "test"},
            {"id": "buildings", "type": "fill-extrusion", "source": "test", "paint": {"fill-extrusion-height": 20}},
            {"id": "hidden-buildings", "type": "fill-extrusion", "source": "test", "layout": {"visibility": "none"}}
          ]
        }
    """.trimIndent()

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
