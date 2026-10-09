package org.imunav.app.maps

import android.graphics.Color
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
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
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.pow

/** Create the map's own layers once (route, position, destination, towers), drawn above the base map. */
fun addNavigationLayers(s: Style) {
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

/** Encoding a large polyline belongs on a worker; both renderers share this representation. */
suspend fun routeFeatures(route: Route?): FeatureCollection {
    val coordinates = route?.geometry?.map { point ->
        currentCoroutineContext().ensureActive()
        Point.fromLngLat(point.lon, point.lat)
    }
    return FeatureCollection.fromFeatures(
        if (coordinates == null) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(coordinates))),
    )
}

/** Clear missing coordinates instead of leaving a stale trusted-looking marker on either display. */
fun updateMapPoint(style: Style, source: String, point: GeoPoint?) {
    style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(
        FeatureCollection.fromFeatures(if (point == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)))),
    )
}

/** The same uncertainty radius is shown on the phone and car, in meters at every zoom level. */
fun updateMapPosition(style: Style, position: GeoPoint?, accuracyM: Double?) {
    updateMapPoint(style, "marker", position)
    if (position == null) return
    val meters = (accuracyM ?: 0.0).coerceAtLeast(0.0)
    val mPerPx0 = 156_543.03 * cos(Math.toRadians(position.lat))
    (style.getLayer("accuracy") as? CircleLayer)?.setProperties(
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
