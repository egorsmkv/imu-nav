package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the shipped JNI loader, importer and Route adapter together on a public synthetic network. */
class RustOfflineGraphTest {
    private val osm = """
        <osm version="0.6">
        <node id="1" lat="50.45" lon="30.5"/><node id="2" lat="50.46" lon="30.5"/>
        <node id="3" lat="50.46" lon="30.51"/><node id="4" lat="50.47" lon="30.5"/>
        <way id="1"><nd ref="1"/><nd ref="2"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="name" v="Northway"/><tag k="maxspeed" v="50"/></way>
        <way id="2"><nd ref="2"/><nd ref="3"/><tag k="highway" v="secondary"/><tag k="name" v="Eastway"/><tag k="maxspeed" v="30"/></way>
        </osm>
    """.trimIndent()

    @Test
    fun rustImportAndNativeRoutePreserveTheAppContract() {
        val temporary = createTempDirectory().toFile()
        try {
            val input = File(temporary, "test.osm").also { it.writeText(osm) }
            val pack = File(temporary, "pack")
            val info = buildGraph(input, pack, "Test", minNetworkSize = 0, withSearch = false)
            assertEquals(PackInfo.FORMAT, info.routingFormat)
            OfflineGraph.load(pack).use { graph ->
                val points = listOf(GeoPoint(50.4502, 30.5001), GeoPoint(50.4601, 30.5099))
                val route = graph.route(points)
                assertTrue(route.length in 1700.0..1900.0)
                assertTrue(route.steps.any { it.type == "turn" && it.modifier == "right" && it.name == "Eastway" })
                assertEquals("depart", route.steps.first().type)
                assertEquals("arrive", route.steps.last().type)
                assertEquals(route.geometry.size - 1, route.segmentSpeedMps.size)
                assertTrue(route.maxspeedKmh.containsAll(listOf(50, 30)))
                assertTrue(graph.route(points, TravelMode.FOOT).durationS > route.durationS)
                RoutingRequest().use { request ->
                    request.cancel()
                    assertFailsWith<CancellationException> { graph.route(points, request = request) }
                }
                val matched = graph.mapMatch(listOf(points.first(), GeoPoint(50.4599, 30.5001), points.last()))
                assertTrue(matched.lengthM in 1700.0..1900.0)
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun oldPacksAreRejectedBeforeOpeningNativeFiles() {
        val temporary = createTempDirectory().toFile()
        try {
            File(temporary, PackInfo.FILE).writeText(PackInfo("old", "", "", doubleArrayOf(50.0, 51.0, 30.0, 31.0), 0).toJson())
            assertFailsWith<OfflineRoutingException> { OfflineGraph.load(temporary) }
        } finally {
            temporary.deleteRecursively()
        }
    }
}
