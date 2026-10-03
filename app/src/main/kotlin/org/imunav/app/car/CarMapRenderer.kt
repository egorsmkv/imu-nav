package org.imunav.app.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.UiState
import org.imunav.app.maps.addNavigationLayers
import org.imunav.app.maps.mapStyle
import org.imunav.app.maps.routeFeatures
import org.imunav.app.maps.updateMapPoint
import org.imunav.app.maps.updateMapPosition
import org.imunav.core.route.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.log2

/** Renders MapLibre directly into the host surface; every replacement owns a complete GL lifecycle. */
class CarMapRenderer(
    private val context: CarContext,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val onVisibility: (Boolean) -> Unit,
    private val onFailure: () -> Unit,
) : SurfaceCallback {
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var encodedRoute: Route? = null
    private var routeJob: Job? = null
    private var styleKey: Pair<Boolean, String?>? = null
    private var state = UiState()
    private var visible = false
    private var resumed = false
    private var width = 0
    private var height = 0
    private var visibleArea = Rect()
    private var stableArea = Rect()
    private var following = true
    private var closed = false

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (closed) {
            surfaceContainer.surface?.release()
            return
        }
        release()
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        if (surface?.isValid != true || width <= 0 || height <= 0) return
        runCatching {
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "IMU Nav",
                width,
                height,
                surfaceContainer.dpi.coerceAtLeast(1),
                surface,
                0,
            )
            val targetDisplay = requireNotNull(display).display
            val window = Presentation(context, targetDisplay)
            presentation = window
            val mapView = MapView(window.context)
            view = mapView
            mapView.onCreate(null)
            window.setContentView(mapView)
            window.show()
            resumeIfVisible()
            mapView.getMapAsync { loaded ->
                if (view !== mapView) return@getMapAsync
                map = loaded
                loaded.uiSettings.isLogoEnabled = false
                loaded.uiSettings.isAttributionEnabled = true
                val start = graph.mapStart.initialView()
                loaded.cameraPosition = CameraPosition.Builder().target(LatLng(start.point.lat, start.point.lon)).zoom(start.zoom).build()
                update(state)
                padding()
            }
        }.onFailure {
            release()
            onFailure()
        }
    }

    /** Session visibility and surface availability both gate rendering and idle sensor ownership. */
    fun setVisible(value: Boolean) {
        if (closed) return
        visible = value
        resumeIfVisible()
    }

    private fun resumeIfVisible() {
        val target = visible && view != null
        if (target != resumed) {
            if (target) {
                view?.onStart()
                view?.onResume()
            } else {
                view?.onPause()
                view?.onStop()
            }
            resumed = target
        }
        onVisibility(target)
    }

    /** Route encoding runs only when geometry changes, not on every guidance update. */
    fun update(ui: UiState) {
        if (closed) return
        if (ui.guidance.active && !state.guidance.active) {
            following = true
            map?.moveCamera(CameraUpdateFactory.zoomTo(16.0))
        }
        state = ui
        view?.setMaximumFps(graph.powerProfile.value.mapMaxFps)
        val loaded = map ?: return
        val dark = context.isDarkMode
        val key = dark to if (graph.offlineMap.status.value.offlineInUse) graph.offlineMap.styleJson(dark) else null
        if (styleKey != key) {
            styleKey = key
            style = null
            loaded.setStyle(mapStyle(key.second, dark)) { newStyle ->
                if (map !== loaded || styleKey != key) return@setStyle
                addNavigationLayers(newStyle)
                style = newStyle
                encodedRoute = null
                update(state)
            }
        }
        val targetStyle = style ?: return
        val route = ui.guidance.route ?: ui.previewRoute
        if (encodedRoute !== route) {
            encodedRoute = route
            routeJob?.cancel()
            routeJob = scope.launch {
                val (features, bounds) = withContext(Dispatchers.Default) {
                    routeFeatures(route) to route?.geometry?.takeIf { it.size > 1 }?.let { points ->
                        LatLngBounds.from(points.maxOf { it.lat }, points.maxOf { it.lon }, points.minOf { it.lat }, points.minOf { it.lon })
                    }
                }
                if (style === targetStyle) {
                    targetStyle.getSourceAs<GeoJsonSource>("route")?.setGeoJson(features)
                    if (!state.guidance.active && bounds != null) loaded.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 32))
                }
            }
        }
        updateMapPoint(targetStyle, "dest", ui.destination ?: ui.guidance.destination)
        updateMapPosition(targetStyle, ui.currentPosition, if (ui.guidance.active) ui.guidance.uncertaintyM else ui.trustedAccuracyM)
        if (following && (ui.guidance.active || route == null)) {
            val position = ui.currentPosition ?: ui.manualStart ?: ui.destination ?: return
            loaded.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(loaded.cameraPosition).target(LatLng(position.lat, position.lon))
                        .bearing(if (ui.guidance.active) ui.guidance.bearingDeg.toDouble() else 0.0).build(),
                ),
            )
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        padding()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        padding()
    }

    private fun padding() {
        val area = Rect(0, 0, width, height)
        if (!stableArea.isEmpty && !area.intersect(stableArea)) return
        if (!visibleArea.isEmpty && !area.intersect(visibleArea)) return
        map?.moveCamera(CameraUpdateFactory.paddingTo(area.left.toDouble(), area.top.toDouble(), (width - area.right).toDouble(), (height - area.bottom).toDouble()))
        map?.uiSettings?.setAttributionMargins(area.left + 8, area.top + 8, width - area.right + 8, height - area.bottom + 8)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        following = false
        map?.scrollBy(-distanceX, -distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor.isFinite() && scaleFactor > 0) zoom(log2(scaleFactor.toDouble()))
    }

    fun zoom(delta: Double) {
        map?.let { it.moveCamera(CameraUpdateFactory.zoomTo((it.cameraPosition.zoom + delta).coerceIn(it.minZoomLevel, it.maxZoomLevel))) }
    }

    fun recenter() {
        following = true
        update(state)
    }

    /** Forward taps so MapLibre attribution remains accessible on the projected display. */
    override fun onClick(x: Float, y: Float) {
        val target = view ?: return
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    /** Release native GL resources before dismissing the window and returning the host surface. */
    fun release() {
        routeJob?.cancel()
        if (resumed) {
            view?.onPause()
            view?.onStop()
        }
        resumed = false
        view?.onDestroy()
        presentation?.dismiss()
        display?.release()
        surface?.release()
        view = null
        map = null
        style = null
        styleKey = null
        encodedRoute = null
        presentation = null
        display = null
        surface = null
        visibleArea = Rect()
        stableArea = Rect()
        if (!closed) onVisibility(false)
    }

    /** Ignore late host callbacks after a session disconnects; they must not recreate ownership. */
    fun close() {
        closed = true
        release()
    }
}
