package org.blinddriver.app.ui

import android.graphics.Color
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.blinddriver.app.cells.TowerLayer
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.route.Route
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
import kotlin.math.cos
import kotlin.math.pow

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private val KYIV = LatLng(50.4501, 30.5234)

/** Imperative handle for map buttons (zoom, re-center) living outside the map composable. */
@Stable
class MapController {
    internal var map: MapLibreMap? = null

    /** Zoom picked by the user; while following, it replaces the automatic zoom. */
    var followZoom by mutableStateOf<Double?>(null)
        internal set

    fun zoomBy(delta: Double) {
        val m = map ?: return
        val z = (m.cameraPosition.zoom + delta).coerceIn(m.minZoomLevel, m.maxZoomLevel)
        followZoom = z
        m.animateCamera(CameraUpdateFactory.zoomTo(z), 250)
    }

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
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var style by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val centerChanged by rememberUpdatedState(onCenterChanged)
    val viewportChanged by rememberUpdatedState(onViewport)
    val userPan by rememberUpdatedState(onUserPan)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            controller.map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(KYIV).zoom(12.0).build()
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
            m.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { s ->
                addLayers(s)
                style = s
                reportViewport()
            }
        }
    }

    // Keep the followed position, compass and attribution inside the visible (uncovered) map area.
    LaunchedEffect(style, insetTopPx, insetBottomPx) {
        val m = controller.map ?: return@LaunchedEffect
        val margin = (8 * context.resources.displayMetrics.density).toInt()
        m.moveCamera(CameraUpdateFactory.paddingTo(0.0, insetTopPx.toDouble(), 0.0, insetBottomPx.toDouble()))
        m.uiSettings.setCompassMargins(0, insetTopPx + margin, margin * 2, 0)
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
        if (destination == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
    }

    LaunchedEffect(style, towers) {
        val st = style ?: return@LaunchedEffect
        fun features(list: List<CellTower>) = FeatureCollection.fromFeatures(
            list.map { t -> Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat)).also { it.addStringProperty("radio", t.key.radio.name) } }
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
                    Expression.exponential(2), Expression.zoom(),
                    Expression.stop(0, (meters / mPerPx0).toFloat()),
                    Expression.stop(22, (meters / (mPerPx0 / 2.0.pow(22))).toFloat()),
                )
            )
        )
    }

    LaunchedEffect(style, position, bearingDeg, following, controller.followZoom) {
        val m = controller.map ?: return@LaunchedEffect
        if (!following || position == null || style == null) return@LaunchedEffect
        val zoom = controller.followZoom ?: 16.0
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(position.lat, position.lon)).zoom(zoom).bearing(bearingDeg.toDouble()).build()
            ),
            450,
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun addLayers(s: Style) {
    for (id in listOf("route", "marker", "dest", "towers", "towers-visible")) s.addSource(GeoJsonSource(id))
    s.addLayer(
        CircleLayer("towers-dot", "towers").withProperties(
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("radio"),
                    Expression.color(Color.GRAY),
                    Expression.stop("GSM", Expression.color(Color.parseColor("#8E24AA"))),
                    Expression.stop("UMTS", Expression.color(Color.parseColor("#FB8C00"))),
                    Expression.stop("LTE", Expression.color(Color.parseColor("#00897B"))),
                    Expression.stop("NR", Expression.color(Color.parseColor("#E53935"))),
                )
            ),
            PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 2f), Expression.stop(16, 6f))),
            PropertyFactory.circleOpacity(0.8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(0.5f),
        )
    )
    s.addLayer(
        CircleLayer("towers-visible-ring", "towers-visible").withProperties(
            PropertyFactory.circleColor(Color.TRANSPARENT),
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleStrokeColor(Color.parseColor("#D32F2F")),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    // Route with a darker casing, like native map apps.
    s.addLayer(
        LineLayer("route-casing", "route").withProperties(
            PropertyFactory.lineColor(Color.parseColor("#0B3D91")),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 5f), Expression.stop(17, 13f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        LineLayer("route-line", "route").withProperties(
            PropertyFactory.lineColor(Color.parseColor("#1A73E8")),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 3f), Expression.stop(17, 9f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        CircleLayer("dest-dot", "dest").withProperties(
            PropertyFactory.circleColor(Color.parseColor("#D93025")),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    // Position: translucent accuracy circle under a blue dot with a white ring.
    s.addLayer(
        CircleLayer("accuracy", "marker").withProperties(
            PropertyFactory.circleColor(Color.parseColor("#1A73E8")),
            PropertyFactory.circleOpacity(0.15f),
            PropertyFactory.circleStrokeColor(Color.parseColor("#1A73E8")),
            PropertyFactory.circleStrokeOpacity(0.4f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circleRadius(0f),
        )
    )
    s.addLayer(
        CircleLayer("marker-dot", "marker").withProperties(
            PropertyFactory.circleColor(Color.parseColor("#1A73E8")),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
}
