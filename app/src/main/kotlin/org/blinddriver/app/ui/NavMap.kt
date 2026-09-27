package org.blinddriver.app.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.route.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private val KYIV = LatLng(50.4501, 30.5234)

/**
 * MapLibre map showing the route, the dead-reckoned marker with its uncertainty radius, and the
 * destination. Long-press picks a destination.
 */
@Composable
fun NavMap(
    route: Route?,
    position: GeoPoint?,
    bearingDeg: Float,
    uncertaintyM: Double,
    destination: GeoPoint?,
    follow: Boolean,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    onCenterChanged: (GeoPoint) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val centerChanged by rememberUpdatedState(onCenterChanged)

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
            map = m
            m.cameraPosition = CameraPosition.Builder().target(KYIV).zoom(12.0).build()
            m.addOnCameraIdleListener {
                m.cameraPosition.target?.let { centerChanged(GeoPoint(it.latitude, it.longitude)) }
            }
            m.addOnMapLongClickListener { latLng ->
                longPress(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addSource(GeoJsonSource("route"))
                s.addSource(GeoJsonSource("marker"))
                s.addSource(GeoJsonSource("dest"))
                s.addLayer(
                    LineLayer("route-line", "route").withProperties(
                        PropertyFactory.lineColor(Color.parseColor("#1E88E5")),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
                s.addLayer(
                    CircleLayer("dest-dot", "dest").withProperties(
                        PropertyFactory.circleColor(Color.parseColor("#D32F2F")),
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                    )
                )
                s.addLayer(
                    CircleLayer("marker-dot", "marker").withProperties(
                        PropertyFactory.circleColor(Color.parseColor("#FFD500")),
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleStrokeColor(Color.parseColor("#1E3A5F")),
                        PropertyFactory.circleStrokeWidth(3f),
                    )
                )
                style = s
            }
        }
    }

    // Route line
    LaunchedEffect(style, route) {
        val src = style?.getSourceAs<GeoJsonSource>("route") ?: return@LaunchedEffect
        if (route == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            val line = LineString.fromLngLats(route.geometry.map { Point.fromLngLat(it.lon, it.lat) })
            src.setGeoJson(Feature.fromGeometry(line))
        }
    }

    LaunchedEffect(style, destination) {
        val src = style?.getSourceAs<GeoJsonSource>("dest") ?: return@LaunchedEffect
        if (destination == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
    }

    LaunchedEffect(style, position, bearingDeg) {
        val src = style?.getSourceAs<GeoJsonSource>("marker") ?: return@LaunchedEffect
        if (position == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)))
        if (follow) {
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(position.lat, position.lon)).zoom(if (uncertaintyM > 200) 14.5 else 16.0).bearing(bearingDeg.toDouble()).build()
                ),
                450,
            )
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
