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
import org.imunav.app.maps.MapMemoryCallbacks
import org.imunav.app.maps.addNavigationLayers
import org.imunav.app.maps.mapStyle
import org.imunav.app.maps.routeFeatures
import org.imunav.app.maps.updateMapPoint
import org.imunav.app.maps.updateMapPosition
import org.imunav.core.geo.GeoPoint
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
    private var memory: MapMemoryCallbacks? = null
    private var prefetch: Int? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleInitialized = false
    private var mapInitialized = false
    private var encodedRoute: Route? = null
    private var routeInitialized = false
    private var routeJob: Job? = null
    private var styleGeneration = 0L
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
    private val mapUpdates = CarMapUpdates()
    private var guidanceZoomPending = false

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
            memory = MapMemoryCallbacks(context, mapView::onLowMemory)
            window.setContentView(mapView)
            window.show()
            resumeIfVisible()
            mapView.getMapAsync { loaded ->
                if (view !== mapView) return@getMapAsync
                map = loaded
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
            onVisibility(target)
            if (target) {
                update(state)
                padding()
            } else {
                if (routeJob?.isActive == true) {
                    routeJob?.cancel()
                    routeInitialized = false
                }
            }
        }
    }

    /** Route encoding runs only when geometry changes, not on every guidance update. */
    fun update(ui: UiState) {
        if (closed) return
        if (ui.guidance.active && !state.guidance.active) {
            following = true
            guidanceZoomPending = true
        }
        state = ui
        if (!resumed) return
        val loaded = map ?: return
        initializeMap(loaded)
        val desiredPrefetch = graph.powerProfile.value.mapPrefetchZoomDelta
        if (prefetch != desiredPrefetch) {
            loaded.setPrefetchZoomDelta(desiredPrefetch)
            prefetch = desiredPrefetch
        }
        if (guidanceZoomPending) {
            if (ui.guidance.active) loaded.moveCamera(CameraUpdateFactory.zoomTo(16.0))
            guidanceZoomPending = false
        }
        val dark = context.isDarkMode
        val center = (if (following) ui.currentPosition else null) ?: loaded.cameraPosition.target?.let { GeoPoint(it.latitude, it.longitude) }
        val key = dark to if (graph.offlineMap.status.value.offlineInUse) graph.offlineMap.styleJson(dark, center) else null
        if (styleKey != key) {
            styleKey = key
            val generation = ++styleGeneration
            style = null
            loaded.setStyle(mapStyle(key.second, dark)) { newStyle ->
                if (map !== loaded || styleKey != key || styleGeneration != generation) return@setStyle
                style = newStyle
                styleInitialized = false
                update(state)
            }
        }
        val targetStyle = style ?: return
        initializeStyle(targetStyle)
        updateRoute(ui, loaded, targetStyle)
        val changes = mapUpdates.update(carMapContent(ui, following, graph.powerProfile.value.mapMaxFps), resumed) ?: return
        if (changes.maximumFps) view?.setMaximumFps(changes.content.maximumFps)
        updateMarkers(changes, targetStyle)
        updateCamera(changes, loaded)
    }

    /** Native setup is deferred too when the surface or style finishes loading while paused. */
    private fun initializeMap(loaded: MapLibreMap) {
        if (mapInitialized) return
        loaded.uiSettings.isLogoEnabled = false
        loaded.uiSettings.isAttributionEnabled = true
        val start = graph.mapStart.initialView()
        loaded.cameraPosition = CameraPosition.Builder().target(LatLng(start.point.lat, start.point.lon)).zoom(start.zoom).build()
        loaded.addOnCameraIdleListener { if (resumed) update(state) }
        mapInitialized = true
    }

    private fun initializeStyle(targetStyle: Style) {
        if (styleInitialized) return
        addNavigationLayers(targetStyle)
        routeInitialized = false
        mapUpdates.reset()
        styleInitialized = true
    }

    /** Geometry encoding runs only for a new route or style and is cancelled while hidden. */
    private fun updateRoute(ui: UiState, loaded: MapLibreMap, targetStyle: Style) {
        val route = ui.guidance.route ?: ui.previewRoute
        if (!routeInitialized || encodedRoute !== route) {
            routeInitialized = true
            encodedRoute = route
            routeJob?.cancel()
            routeJob = scope.launch {
                val (features, bounds) = withContext(Dispatchers.Default) {
                    routeFeatures(route) to route?.geometry?.takeIf { it.size > 1 }?.let { points ->
                        LatLngBounds.from(points.maxOf { it.lat }, points.maxOf { it.lon }, points.minOf { it.lat }, points.minOf { it.lon })
                    }
                }
                if (resumed && style === targetStyle && encodedRoute === route) {
                    targetStyle.getSourceAs<GeoJsonSource>("route")?.setGeoJson(features)
                    if (!state.guidance.active && bounds != null) loaded.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 32))
                }
            }
        }
    }

    /** Position and destination sources are independent, so changing either need not rebuild both. */
    private fun updateMarkers(changes: CarMapChanges, targetStyle: Style) {
        if (changes.destination) updateMapPoint(targetStyle, "dest", changes.content.destination)
        if (changes.position) updateMapPosition(targetStyle, changes.content.position.first, changes.content.position.second)
    }

    /** A recenter gesture invalidates this cache; ordinary UI/log updates preserve the camera. */
    private fun updateCamera(changes: CarMapChanges, loaded: MapLibreMap) {
        val camera = changes.content.camera ?: return
        if (changes.camera) {
            loaded.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(loaded.cameraPosition).target(LatLng(camera.first.lat, camera.first.lon)).bearing(camera.second).build(),
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
        if (!resumed) return
        val area = Rect(0, 0, width, height)
        if (!stableArea.isEmpty && !area.intersect(stableArea)) return
        if (!visibleArea.isEmpty && !area.intersect(visibleArea)) return
        map?.moveCamera(CameraUpdateFactory.paddingTo(area.left.toDouble(), area.top.toDouble(), (width - area.right).toDouble(), (height - area.bottom).toDouble()))
        map?.uiSettings?.setAttributionMargins(area.left + 8, area.top + 8, width - area.right + 8, height - area.bottom + 8)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        following = false
        mapUpdates.invalidateCamera()
        if (!resumed) return
        map?.scrollBy(-distanceX, -distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor.isFinite() && scaleFactor > 0) zoom(log2(scaleFactor.toDouble()))
    }

    fun zoom(delta: Double) {
        if (!resumed) return
        map?.let { it.moveCamera(CameraUpdateFactory.zoomTo((it.cameraPosition.zoom + delta).coerceIn(it.minZoomLevel, it.maxZoomLevel))) }
    }

    fun recenter() {
        mapUpdates.invalidateCamera()
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
        memory?.close()
        memory = null
        prefetch = null
        routeJob?.cancel()
        mapUpdates.reset()
        if (resumed) {
            runCatching { view?.onPause() }
            runCatching { view?.onStop() }
        }
        resumed = false
        runCatching { view?.onDestroy() }
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        runCatching { surface?.release() }
        view = null
        map = null
        mapInitialized = false
        style = null
        styleInitialized = false
        styleKey = null
        encodedRoute = null
        routeInitialized = false
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
