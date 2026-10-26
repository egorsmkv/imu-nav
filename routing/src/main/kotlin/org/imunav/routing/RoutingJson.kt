package org.imunav.routing

import com.fasterxml.jackson.core.JsonParser
import org.imunav.core.geo.GeoPoint
import org.imunav.core.net.WireJson
import org.imunav.core.net.elements
import org.imunav.core.net.fields
import org.imunav.core.net.finiteValue
import org.imunav.core.net.intValueChecked
import org.imunav.core.net.nullable
import org.imunav.core.net.textValue
import org.imunav.core.route.ManeuverModifier
import org.imunav.core.route.ManeuverType
import org.imunav.core.route.Route
import org.imunav.core.route.Step

/** Optional accuracy distinguishes map matching from routing without an untyped request dictionary. */
internal data class RoutingQuery(val points: List<GeoPoint>, val accuracyM: Double? = null)

/** Native JSON is consumed directly into route values; its on-disk and JNI formats stay unchanged. */
internal object RoutingJson {
    fun request(query: RoutingQuery): String = WireJson.encode {
        writeStartObject()
        writeArrayFieldStart("points")
        query.points.forEach { point ->
            require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
            writeStartArray()
            writeNumber(point.lat)
            writeNumber(point.lon)
            writeEndArray()
        }
        writeEndArray()
        query.accuracyM?.let {
            require(it.isFinite() && it > 0.0)
            writeNumberField("accuracy", it)
        }
        writeEndObject()
    }

    fun route(text: String): Route = WireJson.decode(text) {
        var geometry: List<GeoPoint>? = null
        var steps: List<Step>? = null
        var duration: Double? = null
        var limits: List<Int?> = emptyList()
        var speeds: List<Double?> = emptyList()
        var elevation: List<Double>? = null
        fields { name ->
            when (name) {
                "geometry" -> geometry = elements { point() }
                "steps" -> steps = elements { step() }
                "durationS" -> duration = nonnegativeValue()
                "maxspeedKmh" -> limits = nullable { elements { nullable { intValueChecked(0) } } }.orEmpty()
                "segmentSpeedMps" -> speeds = nullable { elements { nullable { nonnegativeValue() } } }.orEmpty()
                "elevationM" -> elevation = nullable { elements { finiteValue() } }?.takeIf { it.isNotEmpty() }
                else -> skipChildren()
            }
        }
        val points = requireNotNull(geometry)
        val maneuvers = requireNotNull(steps)
        require(maneuvers.all { it.geometryIndex in points.indices })
        val segments = (points.size - 1).coerceAtLeast(0)
        require(limits.isEmpty() || limits.size == segments)
        require(speeds.isEmpty() || speeds.size == segments)
        Route(points, maneuvers, requireNotNull(duration), limits, summary = "offline", segmentSpeedMps = speeds, elevationM = elevation?.toDoubleArray())
    }

    private fun JsonParser.point(): GeoPoint {
        val pair = elements { finiteValue() }
        require(pair.size == 2 && pair[0] in -90.0..90.0 && pair[1] in -180.0..180.0)
        return GeoPoint(pair[0], pair[1])
    }

    private fun JsonParser.nonnegativeValue(): Double = finiteValue().also { require(it >= 0.0) }

    private fun JsonParser.step(): Step {
        var type: ManeuverType? = null
        var modifier: ManeuverModifier? = null
        var name: String? = null
        var distance: Double? = null
        var duration: Double? = null
        var geometryIndex: Int? = null
        var exit: Int? = null
        fields { field ->
            when (field) {
                "type" -> type = ManeuverType.parse(textValue())
                "modifier" -> modifier = ManeuverModifier.parse(nullable { textValue() })
                "name" -> name = textValue()
                "distanceM" -> distance = nonnegativeValue()
                "durationS" -> duration = nonnegativeValue()
                "geometryIndex" -> geometryIndex = intValueChecked(0)
                "roundaboutExit" -> exit = nullable { intValueChecked(0) }
                else -> skipChildren()
            }
        }
        return Step(requireNotNull(type), modifier, requireNotNull(name), requireNotNull(distance), requireNotNull(duration), requireNotNull(geometryIndex), exit)
    }
}
