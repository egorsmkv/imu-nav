package org.imunav.app.maps

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.graphics.createBitmap
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.pow

private const val ROUTE_BLUE = "#1A73E8"
private const val PASSED_LINE = "#9AA0A6"
private const val PASSED_CASING = "#5F6368"
private const val HEADING_IMAGE = "heading-cone"
private const val DEST_RED = "#D93025"
private const val ARROW_IMAGE = "nav-arrow"
private const val ARROW_LAYER = "marker-arrow"
private const val DEST_LABEL_IMAGE = "dest-label"
private const val DEST_LABEL_LAYER = "dest-label"

/** Size of the position arrow image (dp), its white outline width (dp) and shadow opacity. */
private const val ARROW_SIZE_DP = 40f
private const val ARROW_OUTLINE_DP = 2.5f
private const val ARROW_SHADOW_ALPHA = 0x55

/** Destination label: text size (sp), inner padding and gap above the pin (dp). */
private const val DEST_LABEL_TEXT_SP = 14f
private const val DEST_LABEL_PADDING_DP = 8f
private const val DEST_LABEL_GAP_DP = 12f

/** Size of the heading beam image (it is drawn around the position dot), dp. */
private const val HEADING_SIZE_DP = 80f

/** Half the opening angle of the heading beam, degrees (Google Maps uses a similar width). */
private const val HEADING_HALF_ANGLE_DEG = 30f
private const val HEADING_ALPHA_NEAR = 0x99

/** Create the map's own layers once (route, position, destination, towers), drawn above the base map. */
fun addNavigationLayers(s: Style) {
    for (id in listOf("route", "route-alternatives", "route-passed", "marker", "heading", "dest", "via", "towers", "towers-visible")) s.addSource(GeoJsonSource(id))
    s.addImage(HEADING_IMAGE, headingBeam())
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
    // Route with a darker casing, like native map apps; the part already driven is grey on top.
    s.addLayer(routeLine("route-alternative-casing", "route-alternatives", PASSED_CASING, casing = true))
    s.addLayer(routeLine("route-alternative-line", "route-alternatives", PASSED_LINE, casing = false))
    s.addLayer(routeLine("route-casing", "route", "#0B3D91", casing = true))
    s.addLayer(routeLine("route-line", "route", ROUTE_BLUE, casing = false))
    s.addLayer(routeLine("route-passed-casing", "route-passed", PASSED_CASING, casing = true))
    s.addLayer(routeLine("route-passed-line", "route-passed", PASSED_LINE, casing = false))
    s.addLayer(
        CircleLayer("via-dot", "via").withProperties(
            PropertyFactory.circleColor("#F9AB00".toColorInt()),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    s.addLayer(
        CircleLayer("dest-dot", "dest").withProperties(
            PropertyFactory.circleColor(DEST_RED.toColorInt()),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Position: translucent accuracy circle, the heading beam, then a blue dot with a white ring.
    s.addLayer(
        CircleLayer("accuracy", "marker").withProperties(
            PropertyFactory.circleColor(ROUTE_BLUE.toColorInt()),
            PropertyFactory.circleOpacity(0.15f),
            PropertyFactory.circleStrokeColor(ROUTE_BLUE.toColorInt()),
            PropertyFactory.circleStrokeOpacity(0.4f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circleRadius(0f),
        ),
    )
    s.addLayer(
        SymbolLayer("heading-beam", "heading").withProperties(
            PropertyFactory.iconImage(HEADING_IMAGE),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            // Rotate with the map, so the beam points to the same street when the map turns.
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
    s.addLayer(
        CircleLayer("marker-dot", "marker").withProperties(
            PropertyFactory.circleColor(ROUTE_BLUE.toColorInt()),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Navigation arrow: lies flat on the (tilted) map and turns with the heading, like the
    // MapLibre/GraphHopper navigation puck. Shown instead of the dot when a heading is known.
    s.addImage(ARROW_IMAGE, navigationArrow())
    s.addLayer(
        SymbolLayer(ARROW_LAYER, "heading").withProperties(
            PropertyFactory.iconImage(ARROW_IMAGE),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.visibility(Property.NONE),
        ),
    )
    // Route length / distance left, above the destination pin; the image is redrawn when the text changes.
    s.addLayer(
        SymbolLayer(DEST_LABEL_LAYER, "dest").withProperties(
            PropertyFactory.iconImage(DEST_LABEL_IMAGE),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, -DEST_LABEL_GAP_DP)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.visibility(Property.NONE),
        ),
    )
}

/**
 * The position arrow: a chevron pointing up (north) with a notch at its base, blue with a white
 * outline and a soft shadow so it stays visible on light and dark maps. The map rotates it.
 */
private fun navigationArrow(): Bitmap {
    val density = Resources.getSystem().displayMetrics.density
    val sizePx = (ARROW_SIZE_DP * density).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(sizePx, sizePx)
    val size = sizePx.toFloat()
    // Tip, right corner, notch, left corner; the shape's centroid is near the image center.
    val path = Path().apply {
        moveTo(size * 0.5f, size * 0.12f)
        lineTo(size * 0.82f, size * 0.86f)
        lineTo(size * 0.5f, size * 0.68f)
        lineTo(size * 0.18f, size * 0.86f)
        close()
    }
    val canvas = Canvas(bitmap)
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(ARROW_SHADOW_ALPHA, 0, 0, 0)
        maskFilter = BlurMaskFilter(2f * density, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawPath(path, shadow)
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROUTE_BLUE.toColorInt() })
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = ARROW_OUTLINE_DP * density
            strokeJoin = Paint.Join.ROUND
        },
    )
    return bitmap
}

/** A rounded label with [text], dark or light to match the map. */
private fun destinationLabel(text: String, dark: Boolean): Bitmap {
    val metrics = Resources.getSystem().displayMetrics
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.WHITE else "#202124".toColorInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEST_LABEL_TEXT_SP, metrics)
        typeface = Typeface.DEFAULT_BOLD
    }
    val padding = DEST_LABEL_PADDING_DP * metrics.density
    val bounds = Rect().also { textPaint.getTextBounds(text, 0, text.length, it) }
    val width = (textPaint.measureText(text) + 2 * padding).toInt().coerceAtLeast(1)
    val height = (textPaint.fontMetrics.let { it.descent - it.ascent } + padding).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val radius = height / 2f
    canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) "#303134".toColorInt() else Color.WHITE })
    canvas.drawRoundRect(
        RectF(box).apply { inset(1f, 1f) },
        radius,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DEST_RED.toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = metrics.density
        },
    )
    canvas.drawText(text, padding, height / 2f - bounds.exactCenterY(), textPaint)
    return bitmap
}

/**
 * Show [text] (route length or distance left) above the destination, or hide the label when null.
 * Call it only when the text changes: it redraws a small bitmap.
 */
fun updateDestinationLabel(style: Style, text: String?, dark: Boolean) {
    val layer = style.getLayer(DEST_LABEL_LAYER) ?: return
    if (text.isNullOrBlank()) {
        layer.setProperties(PropertyFactory.visibility(Property.NONE))
        return
    }
    style.addImage(DEST_LABEL_IMAGE, destinationLabel(text, dark))
    layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
}

/** One of the route lines: the wide [casing] below or the narrower colored line on top. */
private fun routeLine(id: String, source: String, color: String, casing: Boolean): LineLayer {
    val (low, high) = if (casing) 5f to 13f else 3f to 9f
    return LineLayer(id, source).withProperties(
        PropertyFactory.lineColor(color.toColorInt()),
        PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, low), Expression.stop(17, high))),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
}

/**
 * The "where am I facing" beam: a wedge pointing up (north) that fades out with distance from its
 * tip. The tip is the image center, so the beam starts at the position dot; the map rotates it.
 */
private fun headingBeam(): Bitmap {
    val sizePx = (HEADING_SIZE_DP * Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(1)
    val center = sizePx / 2f
    val bitmap = createBitmap(sizePx, sizePx)
    val blue = ROUTE_BLUE.toColorInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            center,
            center,
            center,
            intArrayOf(Color.argb(HEADING_ALPHA_NEAR, Color.red(blue), Color.green(blue), Color.blue(blue)), Color.argb(0, Color.red(blue), Color.green(blue), Color.blue(blue))),
            null,
            Shader.TileMode.CLAMP,
        )
    }
    // Android angles start at 3 o'clock and grow clockwise: "up" is -90°.
    Canvas(bitmap).drawArc(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), -90f - HEADING_HALF_ANGLE_DEG, 2 * HEADING_HALF_ANGLE_DEG, true, paint)
    return bitmap
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

/**
 * The part of [route] already driven: from its start to [passedM] metres along it, ending exactly
 * under the position dot. Empty before the first metre. Built on a worker like [routeFeatures].
 */
suspend fun passedRouteFeatures(route: Route?, passedM: Double?): FeatureCollection {
    if (route == null || passedM == null || passedM <= 0.0 || route.geometry.size < 2) return FeatureCollection.fromFeatures(emptyList())
    val s = passedM.coerceAtMost(route.length)
    val lastFull = route.segmentAt(s)
    val coordinates = buildList {
        for (i in 0..lastFull) {
            currentCoroutineContext().ensureActive()
            add(Point.fromLngLat(route.geometry[i].lon, route.geometry[i].lat))
        }
        val end = route.pointAt(s).point
        add(Point.fromLngLat(end.lon, end.lat))
    }
    return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(coordinates))))
}

/** Clear missing coordinates instead of leaving a stale trusted-looking marker on either display. */
fun updateMapPoint(style: Style, source: String, point: GeoPoint?) {
    updateMapPoints(style, source, listOfNotNull(point))
}

/** Several markers from one source (e.g. the stops of a route). */
fun updateMapPoints(style: Style, source: String, points: List<GeoPoint>) {
    style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(
        FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }),
    )
}

/**
 * The same uncertainty radius is shown on the phone and car, in meters at every zoom level.
 * [headingDeg] turns the beam or arrow (compass bearing, clockwise from north); null hides it.
 * With [arrow], a known heading draws the flat navigation arrow instead of the dot and beam;
 * without a heading the dot stays, since an arrow would claim a direction nobody measured.
 */
fun updateMapPosition(style: Style, position: GeoPoint?, accuracyM: Double?, headingDeg: Float? = null, arrow: Boolean = false) {
    updateMapPoint(style, "marker", position)
    val hasHeading = position != null && headingDeg != null && headingDeg.isFinite()
    style.getSourceAs<GeoJsonSource>("heading")?.setGeoJson(
        FeatureCollection.fromFeatures(
            if (position == null || headingDeg == null || !hasHeading) {
                emptyList()
            } else {
                listOf(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)).also { it.addNumberProperty("bearing", headingDeg) })
            },
        ),
    )
    val showArrow = arrow && hasHeading
    fun visible(show: Boolean) = PropertyFactory.visibility(if (show) Property.VISIBLE else Property.NONE)
    style.getLayer(ARROW_LAYER)?.setProperties(visible(showArrow))
    style.getLayer("marker-dot")?.setProperties(visible(!showArrow))
    style.getLayer("heading-beam")?.setProperties(visible(!showArrow))
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
