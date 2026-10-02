package org.imunav.app.ui

import android.graphics.Color
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
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.imunav.app.MapStartPrefs
import org.imunav.app.cells.TowerLayer
import org.imunav.app.maps.mapStyle
import org.imunav.core.cells.CellTower
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/** Imperative handle for map buttons (zoom, re-center) living outside the map composable. */
@Stable
class MapController {
    internal var map: MapLibreMap? = null

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
    /** Glide the camera between positions, or jump (one frame per update instead of a 450 ms animation). */
    animateCamera: Boolean = true,
    /** Where the camera starts before any position is known (Settings → Map start). */
    initialCenter: GeoPoint = MapStartPrefs.KYIV,
    initialZoom: Double = 12.0,
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
    fun moveMapTo(target: MapViewState) {
        while (mapState < target) {
            if (mapState == MapViewState.CREATED) mapView.onStart() else mapView.onResume()
            mapState = MapViewState.entries[mapState.ordinal + 1]
        }
        while (mapState > target) {
            if (mapState == MapViewState.RESUMED) mapView.onPause() else mapView.onStop()
            mapState = MapViewState.entries[mapState.ordinal - 1]
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> screenState = lifecycle.currentState }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
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
        mapView.getMapAsync { m ->
            controller.map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(LatLng(initialCenter.lat, initialCenter.lon)).zoom(initialZoom).build()
            /** Tell the caller which area is visible (the tower layer loads towers for it). */
            fun reportViewport() {
                val b = m.projection.visibleRegion.latLngBounds
                viewportChanged(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, m.cameraPosition.zoom)
            }
            m.addOnCameraIdleListener {
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
            loadedMap = m
        }
    }

    // (Re)load the style when the map is ready, the theme changes, or an offline pack is installed or removed.
    // Changing the style drops our own layers, so they are added again; the effects below re-run on the new style.
    LaunchedEffect(loadedMap, offlineStyleJson, dark) {
        val m = loadedMap ?: return@LaunchedEffect
        style = null
        m.setStyle(mapStyle(offlineStyleJson, dark)) { s ->
            addLayers(s)
            style = s
            val b = m.projection.visibleRegion.latLngBounds
            viewportChanged(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, m.cameraPosition.zoom)
        }
    }

    // Keep the followed position, compass and attribution inside the visible (uncovered) map area.
    LaunchedEffect(style, insetTopPx, insetBottomPx, cameraInsetStartPx, cameraInsetBottomPx) {
        val m = controller.map ?: return@LaunchedEffect
        val margin = (8 * density).toInt()
        m.moveCamera(CameraUpdateFactory.paddingTo(cameraInsetStartPx.toDouble(), insetTopPx.toDouble(), 0.0, cameraInsetBottomPx.toDouble()))
        m.uiSettings.compassGravity = Gravity.TOP or Gravity.START
        m.uiSettings.setCompassMargins(cameraInsetStartPx + margin, insetTopPx + margin, 0, 0)
        m.uiSettings.setAttributionMargins(margin, 0, 0, insetBottomPx + margin)
    }

    LaunchedEffect(style, route) {
        val src = style?.getSourceAs<GeoJsonSource>("route") ?: return@LaunchedEffect
        if (route == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(route.geometry.map { Point.fromLngLat(it.lon, it.lat) })))
        }
    }

    LaunchedEffect(style, destination) {
        val src = style?.getSourceAs<GeoJsonSource>("dest") ?: return@LaunchedEffect
        if (destination == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
        }
    }

    LaunchedEffect(style, towers) {
        val st = style ?: return@LaunchedEffect

        /** Towers as GeoJSON points, tagged with their radio type for colouring. */
        fun features(list: List<CellTower>) = FeatureCollection.fromFeatures(
            list.map { t -> Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat)).also { it.addStringProperty("radio", t.key.radio.name) } },
        )
        st.getSourceAs<GeoJsonSource>("towers")?.setGeoJson(features(towers?.towers.orEmpty()))
        st.getSourceAs<GeoJsonSource>("towers-visible")?.setGeoJson(features(towers?.visible.orEmpty()))
    }

    LaunchedEffect(style, position, accuracyM) {
        val st = style ?: return@LaunchedEffect
        val marker = st.getSourceAs<GeoJsonSource>("marker") ?: return@LaunchedEffect
        if (position == null) {
            marker.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        marker.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)))
        // Accuracy circle: metres → pixels at each zoom (Web Mercator), interpolated exponentially.
        val meters = (accuracyM ?: 0.0).coerceAtLeast(0.0)
        val mPerPx0 = 156_543.03 * cos(Math.toRadians(position.lat))
        (st.getLayer("accuracy") as? CircleLayer)?.setProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2),
                    Expression.zoom(),
                    Expression.stop(0, (meters / mPerPx0).toFloat()),
                    Expression.stop(22, (meters / (mPerPx0 / 2.0.pow(22))).toFloat()),
                ),
            ),
        )
    }

    LaunchedEffect(maxFps) { mapView.setMaximumFps(maxFps) }

    LaunchedEffect(style, position, bearingDeg, following, controller.followZoom) {
        val m = controller.map ?: return@LaunchedEffect
        if (!following || position == null || style == null) return@LaunchedEffect
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

/** Create the map's own layers once (route, position, destination, towers), drawn above the base map. */
private fun addLayers(s: Style) {
    for (id in listOf("route", "marker", "dest", "towers", "towers-visible")) s.addSource(GeoJsonSource(id))
    s.addLayer(
        CircleLayer("towers-dot", "towers").withProperties(
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("radio"),
                    Expression.color(Color.GRAY),
                    Expression.stop("GSM", Expression.color("#8E24AA".toColorInt())),
                    Expression.stop("UMTS", Expression.color("#FB8C00".toColorInt())),
                    Expression.stop("LTE", Expression.color("#00897B".toColorInt())),
                    Expression.stop("NR", Expression.color("#E53935".toColorInt())),
                ),
            ),
            PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 2f), Expression.stop(16, 6f))),
            PropertyFactory.circleOpacity(0.8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(0.5f),
        ),
    )
    s.addLayer(
        CircleLayer("towers-visible-ring", "towers-visible").withProperties(
            PropertyFactory.circleColor(Color.TRANSPARENT),
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleStrokeColor("#D32F2F".toColorInt()),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Route with a darker casing, like native map apps.
    s.addLayer(
        LineLayer("route-casing", "route").withProperties(
            PropertyFactory.lineColor("#0B3D91".toColorInt()),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 5f), Expression.stop(17, 13f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    s.addLayer(
        LineLayer("route-line", "route").withProperties(
            PropertyFactory.lineColor("#1A73E8".toColorInt()),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 3f), Expression.stop(17, 9f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    s.addLayer(
        CircleLayer("dest-dot", "dest").withProperties(
            PropertyFactory.circleColor("#D93025".toColorInt()),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Position: translucent accuracy circle under a blue dot with a white ring.
    s.addLayer(
        CircleLayer("accuracy", "marker").withProperties(
            PropertyFactory.circleColor("#1A73E8".toColorInt()),
            PropertyFactory.circleOpacity(0.15f),
            PropertyFactory.circleStrokeColor("#1A73E8".toColorInt()),
            PropertyFactory.circleStrokeOpacity(0.4f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circleRadius(0f),
        ),
    )
    s.addLayer(
        CircleLayer("marker-dot", "marker").withProperties(
            PropertyFactory.circleColor("#1A73E8".toColorInt()),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}
