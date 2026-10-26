package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.ManeuverModifier
import org.imunav.core.route.ManeuverType
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Native boundary fixtures keep validation independent of pack loading and JNI availability. */
class RoutingJsonTest {
    private val response = """
        {"geometry":[[50,30],[50.01,30]],"steps":[{"type":"turn","modifier":"left","name":"Example",
         "distanceM":100,"durationS":10,"geometryIndex":0,"roundaboutExit":null}],"durationS":10,
         "maxspeedKmh":[50],"segmentSpeedMps":[10],"elevationM":[100,101]}
    """.trimIndent()

    @Test
    fun nativeRequestsAndResponsesKeepCoordinateOrderAndOptionalFields() {
        val points = listOf(GeoPoint(50.0, 30.0), GeoPoint(50.01, 30.0))
        assertEquals("""{"points":[[50.0,30.0],[50.01,30.0]]}""", RoutingJson.request(RoutingQuery(points)))
        assertEquals("""{"points":[[50.0,30.0],[50.01,30.0]],"accuracy":20.0}""", RoutingJson.request(RoutingQuery(points, 20.0)))
        val route = RoutingJson.route(response)
        assertEquals(points, route.geometry)
        assertEquals(ManeuverType.TURN, route.steps.single().type)
        assertEquals(ManeuverModifier.LEFT, route.steps.single().modifier)
        assertEquals(listOf(50), route.maxspeedKmh)
        assertEquals(listOf(10.0), route.segmentSpeedMps)
        assertContentEquals(doubleArrayOf(100.0, 101.0), route.elevationM)
        assertNull(RoutingJson.route(response.replace("\"modifier\":\"left\"", "\"modifier\":\"\"")).steps.single().modifier)
        assertNull(RoutingJson.route(response.replace("[100,101]", "null")).elevationM)
        assertTrue(RoutingJson.route("""{"geometry":[],"steps":[],"durationS":0}""").geometry.isEmpty())
    }

    @Test
    fun malformedGeometryNumbersAndManeuversAreRejected() {
        val invalid = listOf(
            "{}", "[]", response.dropLast(1), response + " null",
            response.replace("[50,30]", "[50,30,0]"), response.replace("[50,30]", "[50]"),
            response.replace("[50,30]", "[91,30]"), response.replace("[50,30]", "[50,\"30\"]"),
            response.replace("\"durationS\":10", "\"durationS\":1e999"),
            response.replace("\"durationS\":10", "\"durationS\":-1"),
            response.replace("\"geometryIndex\":0", "\"geometryIndex\":0.5"),
            response.replace("\"geometryIndex\":0", "\"geometryIndex\":2147483648"),
            response.replace("\"geometryIndex\":0", "\"geometryIndex\":2"),
            response.replace("\"roundaboutExit\":null", "\"roundaboutExit\":2147483648"),
            response.replace("\"type\":\"turn\"", "\"type\":\"future\""),
            response.replace("\"modifier\":\"left\"", "\"modifier\":\"future\""),
            response.replace("\"name\":\"Example\"", "\"name\":10"),
            response.replace("\"maxspeedKmh\":[50]", "\"maxspeedKmh\":[50,60]"),
            response.replace("[100,101]", "[100]"),
        )
        invalid.forEach { text ->
            val failure = assertFailsWith<IllegalArgumentException> { RoutingJson.route(text) }
            assertEquals("Invalid JSON payload", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun metadataRetainsDefaultsAndRejectsMalformedPresentFields() {
        val legacy = """{"name":"Test","bounds":[-1,1,-2,2]}"""
        val info = requireNotNull(PackInfo.parse(legacy))
        assertEquals(listOf("car"), info.profiles)
        assertEquals(0L, info.sizeBytes)
        assertNull(info.searchFormat)
        val parsed = requireNotNull(PackInfo.parse(info.toJson()))
        assertEquals(info.name, parsed.name)
        assertContentEquals(info.bounds, parsed.bounds)
        assertEquals(info.toJson(), parsed.toJson())
        for (suffix in listOf(
            "\"profiles\":[1]",
            "\"profiles\":[]",
            "\"profiles\":[\"car\",\"car\"]",
            "\"elevation\":\"true\"",
            "\"sizeBytes\":1.5",
            "\"sizeBytes\":9223372036854775808",
        )) {
            assertNull(PackInfo.parse(legacy.dropLast(1) + "," + suffix + "}"))
        }
        assertNull(PackInfo.parse(legacy + "{}"))
        assertNull(PackInfo.parse(legacy.replace("[-1,1,-2,2]", "[-1,1,-2]")))
        assertNull(PackInfo.parse(legacy.replace("[-1,1,-2,2]", "[2,1,-2,2]")))
    }
}
