package org.imunav.app.ui

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.imunav.app.MapStartPrefs
import org.imunav.app.cells.TowerLayer
import org.imunav.app.maps.MapMemoryCallbacks
import org.imunav.app.maps.addNavigationLayers
import org.imunav.app.maps.mapStyle
import org.imunav.app.maps.routeFeatures
import org.imunav.app.maps.updateMapPoint
import org.imunav.app.maps.updateMapPosition
import org.imunav.core.cells.CellTower
import org.imunav.core.geo.GeoPoint
import org.imunav.core.power.MapRenderingBudget
import org.imunav.core.route.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs

/** Imperative handle for map buttons (zoom, re-center) living outside the map composable. */
@Stable
class MapController {
    internal var map by mutableStateOf<MapLibreMap?>(null)

    /** Zoom picked by the user; while following, it replaces the automatic zoom. */
    var followZoom by mutableStateOf<Double?>(null)
        internal set

    /** Zoom in (positive) or out (negative); while following, the new zoom is kept. */
    fun zoomBy(delta: Double) {
        val m = map ?: return
        val z = (m.cameraPosition.zoom + delta).coerceIn(m.minZoomLevel, m.maxZoomLevel)
        followZoom = z
        m.animateCamera(CameraUpdateFactory.zoomTo(z), 250)
    }

    /** Fly the camera to [p] (north up). */
    fun moveTo(p: GeoPoint, zoom: Double? = null) {
        val m = map ?: return
        val z = zoom ?: m.cameraPosition.zoom.coerceAtLeast(14.0)
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(z).bearing(0.0).build()), 500)
    }
}

/**
 * MapLibre map: route, position dot with accuracy circle, destination, optional cell-tower layer.
 * While [following], the camera tracks [position]; any pan gesture calls [onUserPan] so the screen
 * can pause following (standard maps behaviour).
 */
@Composable
fun NavMap(
    controller: MapController,
    dark: Boolean,
    route: Route?,
    position: GeoPoint?,
    accuracyM: Double?,
    bearingDeg: Float,
    destination: GeoPoint?,
    following: Boolean,
    towers: TowerLayer?,
    onLongPress: (GeoPoint) -> Unit,
    onCenterChanged: (GeoPoint) -> Unit,
    onViewport: (south: Double, west: Double, north: Double, east: Double, zoom: Double) -> Unit,
    onUserPan: () -> Unit,
    modifier: Modifier = Modifier,
    /** Heights (px) covered by overlays at the top and bottom; the camera centres between them. */
    insetTopPx: Int = 0,
    insetBottomPx: Int = 0,
    /** Camera-only padding for layouts where an overlay covers just the left or bottom map pane. */
    cameraInsetStartPx: Int = 0,
    cameraInsetBottomPx: Int = insetBottomPx,
    /** Frame-rate cap (power mode); lower = less GPU work while the camera follows the car. */
    maxFps: Int = 60,
    prefetchZoomDelta: Int = MapRenderingBudget.DEFAULT_PREFETCH_ZOOM_DELTA,
    /** Glide the camera between positions, or jump (one frame per update instead of a 450 ms animation). */
    animateCamera: Boolean = true,
    /** Where the camera starts before any position is known (Settings → Map start). */
    initialCenter: GeoPoint = MapStartPrefs.WORLD,
    initialZoom: Double = MapStartPrefs.OVERVIEW_ZOOM,
    /** Zoom while following the position (closer when walking). */
    followZoomDefault: Double = 16.0,
    /** Style of the installed offline map pack; null = online map. */
    offlineStyleJson: String? = null,
    /** False while another screen covers the map: rendering pauses (no GPU/battery use) but nothing is rebuilt. */
    active: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var style by remember { mutableStateOf<Style?>(null) }
    var readyStyle by remember { mutableStateOf<Style?>(null) }
    var appliedRoute by remember { mutableStateOf<Pair<Style, Route?>?>(null) }
    var appliedTowers by remember { mutableStateOf<Pair<Style, TowerLayer?>?>(null) }
    var requestedStyle by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }
    var styleGeneration by remember { mutableStateOf(0L) }
    var disposed by remember { mutableStateOf(false) }
    var initializedMap by remember { mutableStateOf(false) }

    /** The map once MapLibre has created it (before any style is loaded). */
    var loadedMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val centerChanged by rememberUpdatedState(onCenterChanged)
    val viewportChanged by rememberUpdatedState(onViewport)
    val userPan by rememberUpdatedState(onUserPan)

    // The MapView runs (draws) only while the screen is started AND the map is visible. Both inputs
    // are tracked here and one effect moves the MapView between created / started / resumed.
    var screenState by remember { mutableStateOf(Lifecycle.State.INITIALIZED) }
    var mapState by remember { mutableStateOf(MapViewState.CREATED) }
    val renderable = active && screenState.isAtLeast(Lifecycle.State.STARTED)
    val currentlyRenderable by rememberUpdatedState(renderable)
    fun moveMapTo(target: MapViewState) {
        while (mapState < target) {
            if (mapState == MapViewState.CREATED) mapView.onStart() else mapView.onResume()
            mapState = MapViewState.entries[mapState.ordinal + 1]
        }
        while (mapState > target) {
            if (mapState == MapViewState.RESUMED) {
                loadedMap?.cancelTransitions()
                mapView.onPause()
            } else {
                mapView.onStop()
            }
            mapState = MapViewState.entries[mapState.ordinal - 1]
        }
    }
    DisposableEffect(lifecycle) {
        val memory = MapMemoryCallbacks(context, mapView::onLowMemory)
        val observer = LifecycleEventObserver { _, _ -> screenState = lifecycle.currentState }
        lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            memory.close()
            lifecycle.removeObserver(observer)
            controller.map = null
            // Without this every discarded map leaked its native map and GL surface.
            moveMapTo(MapViewState.CREATED)
            mapView.onDestroy()
        }
    }
    LaunchedEffect(active, screenState) {
        moveMapTo(
            when {
                !active || !screenState.isAtLeast(Lifecycle.State.STARTED) -> MapViewState.CREATED
                screenState.isAtLeast(Lifecycle.State.RESUMED) -> MapViewState.RESUMED
                else -> MapViewState.STARTED
            },
        )
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m -> if (!disposed) loadedMap = m }
    }
    LaunchedEffect(loadedMap, renderable) {
        val m = loadedMap ?: return@LaunchedEffect
        if (!renderable || initializedMap) return@LaunchedEffect
        initializedMap = true
        controller.map = m
        m.uiSettings.isCompassEnabled = true
        m.uiSettings.isRotateGesturesEnabled = true
        m.uiSettings.isAttributionEnabled = true
        m.uiSettings.isLogoEnabled = false
        m.cameraPosition = CameraPosition.Builder().target(LatLng(initialCenter.lat, initialCenter.lon)).zoom(initialZoom).build()
        /** Tell the caller which area is visible (the tower layer loads towers for it). */
        fun reportViewport() {
            if (!currentlyRenderable || disposed) return
            val b = m.projection.visibleRegion.latLngBounds
            viewportChanged(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, m.cameraPosition.zoom)
        }
        m.addOnCameraIdleListener {
            if (!currentlyRenderable || disposed) return@addOnCameraIdleListener
            m.cameraPosition.target?.let { centerChanged(GeoPoint(it.latitude, it.longitude)) }
            reportViewport()
        }
        m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) = userPan()
            override fun onMove(detector: MoveGestureDetector) = Unit
            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        })
        m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) = Unit
            override fun onScale(detector: StandardScaleGestureDetector) = Unit
            override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                controller.followZoom = m.cameraPosition.zoom
            }
        })
        m.addOnMapLongClickListener { latLng ->
            longPress(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
    }

    // (Re)load the style when the map is ready, the theme changes, or an offline pack is installed or removed.
    // Changing the style drops our own layers, so they are added again; the effects below re-run on the new style.
    LaunchedEffect(loadedMap, offlineStyleJson, dark, renderable) {
        val m = loadedMap ?: return@LaunchedEffect
        val key = dark to offlineStyleJson
        if (!renderable || requestedStyle == key) return@LaunchedEffect
        requestedStyle = key
        val generation = ++styleGeneration
        style = null
        readyStyle = null
        appliedRoute = null
        appliedTowers = null
        m.setStyle(mapStyle(offlineStyleJson, dark)) { loaded ->
            if (!disposed && loadedMap === m && requestedStyle == key && styleGeneration == generation) style = loaded
        }
    }
    LaunchedEffect(style, renderable) {
        val loaded = style ?: return@LaunchedEffect
        if (!renderable || readyStyle === loaded) return@LaunchedEffect
        addNavigationLayers(loaded)
        readyStyle = loaded
    }

    // Keep the followed position, compass and attribution inside the visible (uncovered) map area.
    LaunchedEffect(renderable, readyStyle, insetTopPx, insetBottomPx, cameraInsetStartPx, cameraInsetBottomPx) {
        if (!renderable) return@LaunchedEffect
        val m = controller.map ?: return@LaunchedEffect
        val margin = (8 * density).toInt()
        m.moveCamera(CameraUpdateFactory.paddingTo(cameraInsetStartPx.toDouble(), insetTopPx.toDouble(), 0.0, cameraInsetBottomPx.toDouble()))
        m.uiSettings.compassGravity = Gravity.TOP or Gravity.START
        m.uiSettings.setCompassMargins(cameraInsetStartPx + margin, insetTopPx + margin, 0, 0)
        m.uiSettings.setAttributionMargins(cameraInsetStartPx + margin, 0, 0, cameraInsetBottomPx + margin)
    }

    LaunchedEffect(renderable, readyStyle, route) {
        if (!renderable) return@LaunchedEffect
        val target = readyStyle ?: return@LaunchedEffect
        if (appliedRoute?.first === target && appliedRoute?.second === route) return@LaunchedEffect
        val features = withContext(Dispatchers.Default) { routeFeatures(route) }
        if (currentlyRenderable && !disposed && readyStyle === target) {
            target.getSourceAs<GeoJsonSource>("route")?.setGeoJson(features)
            appliedRoute = target to route
        }
    }

    LaunchedEffect(renderable, readyStyle, destination) { if (renderable) readyStyle?.let { updateMapPoint(it, "dest", destination) } }

    LaunchedEffect(renderable, readyStyle, towers) {
        if (!renderable) return@LaunchedEffect
        val st = readyStyle ?: return@LaunchedEffect
        if (appliedTowers?.first === st && appliedTowers?.second === towers) return@LaunchedEffect

        /** Towers as GeoJSON points, tagged with their radio type for colouring. */
        fun features(list: List<CellTower>) = FeatureCollection.fromFeatures(
            list.map { t -> Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat)).also { it.addStringProperty("radio", t.key.radio.name) } },
        )
        val (all, visible) = withContext(Dispatchers.Default) {
            features(towers?.towers.orEmpty()) to features(towers?.visible.orEmpty())
        }
        if (currentlyRenderable && !disposed && readyStyle === st) {
            st.getSourceAs<GeoJsonSource>("towers")?.setGeoJson(all)
            st.getSourceAs<GeoJsonSource>("towers-visible")?.setGeoJson(visible)
            appliedTowers = st to towers
        }
    }

    LaunchedEffect(renderable, readyStyle, position, accuracyM) { if (renderable) readyStyle?.let { updateMapPosition(it, position, accuracyM) } }

    LaunchedEffect(renderable, loadedMap, maxFps, prefetchZoomDelta) {
        if (renderable) {
            mapView.setMaximumFps(maxFps)
            loadedMap?.setPrefetchZoomDelta(prefetchZoomDelta)
        }
    }

    LaunchedEffect(renderable, readyStyle, position, bearingDeg, following, controller.followZoom, animateCamera, followZoomDefault) {
        if (!renderable) return@LaunchedEffect
        val m = controller.map ?: return@LaunchedEffect
        if (!following || position == null || readyStyle == null) return@LaunchedEffect
        val zoom = controller.followZoom ?: followZoomDefault
        val cam = m.cameraPosition
        val target = LatLng(position.lat, position.lon)
        // Standing still: don't redraw the map for sub-metre / sub-degree changes.
        val moved = cam.target?.distanceTo(target) ?: Double.MAX_VALUE
        val turned = abs(((bearingDeg - cam.bearing + 540.0) % 360.0) - 180.0)
        if (moved < 1.0 && turned < 2.0 && abs(cam.zoom - zoom) < 0.01) return@LaunchedEffect
        val update = CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(zoom).bearing(bearingDeg.toDouble()).build())
        if (animateCamera) m.animateCamera(update, 450) else m.moveCamera(update)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** MapView's own lifecycle steps, in order. */
private enum class MapViewState { CREATED, STARTED, RESUMED }
