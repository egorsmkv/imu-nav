package org.blinddriver.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.util.shapes.GHPoint
import org.blinddriver.core.geo.GeoPoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Builds a tiny road network on the desktop path (Janino custom model + CH), then loads and routes it
 * on the phone path (PhoneGraphHopper, memory-mapped). Proves the two weightings match.
 */
class OfflineGraphTest {
    /** "Northway" 1.1 km north (maxspeed 50), then right onto "Eastway" 1 km east (maxspeed 30). */
    private fun writeOsm(file: File) {
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<osm version='0.6'>\n")
        val north = (0..11).map { 1000L + it to (50.450 + it * 0.001 to 30.500) }
        val east = (1..14).map { 2000L + it to (50.461 to 30.500 + it * 0.001) }
        // A side street continuing north makes the junction a real choice, so a turn instruction is emitted.
        val stub = (1..3).map { 3000L + it to (50.461 + it * 0.001 to 30.500) }
        (north + east + stub).forEach { (id, p) -> sb.append("<node id='$id' lat='${p.first}' lon='${p.second}' version='1'/>\n") }
        fun way(id: Long, nodes: List<Long>, name: String, highway: String, maxspeed: Int) {
            sb.append("<way id='$id' version='1'>")
            nodes.forEach { sb.append("<nd ref='$it'/>") }
            sb.append("<tag k='highway' v='$highway'/><tag k='name' v='$name'/><tag k='maxspeed' v='$maxspeed'/></way>\n")
        }
        way(1, north.map { it.first }, "Northway", "primary", 50)
        way(2, listOf(1011L) + east.map { it.first }, "Eastway", "secondary", 30)
        way(3, listOf(1011L) + stub.map { it.first }, "Stubway", "residential", 30)
        sb.append("</osm>\n")
        file.writeText(sb.toString())
    }

    @Test
    fun buildOnDesktopRouteOnPhonePath() {
        val tmp = createTempDirectory()
        val osm = File(tmp, "test.osm").also { writeOsm(it) }
        val dir = File(tmp, "graph")
        val info = buildGraph(osm, dir, "test", minNetworkSize = 0)
        assertTrue(File(dir, PackInfo.FILE).exists())
        assertEquals("test", PackInfo.parse(File(dir, PackInfo.FILE).readText())?.name)
        assertTrue(info.bounds[0] < 50.451 && info.bounds[1] > 50.460)

        val start = GeoPoint(50.4502, 30.5001)
        val end = GeoPoint(50.4611, 30.5135)
        OfflineGraph.load(dir).use { g ->
            val route = g.route(listOf(start, end))
            val types = route.steps.map { it.type to it.modifier }
            assertEquals("depart" to null, types.first(), "steps: $types")
            assertTrue(("turn" to "right") in types, "right turn expected: $types")
            assertEquals("arrive", route.steps.last().type)
            assertEquals("Eastway", route.steps.first { it.type == "turn" }.name)
            assertTrue(route.length in 2000.0..2600.0, "length ${route.length}")
            // Turn step points at the junction vertex.
            val turnAt = route.geometry[route.steps.first { it.type == "turn" }.geometryIndex]
            assertEquals(50.461, turnAt.lat, 2e-4)
            assertEquals(30.500, turnAt.lon, 2e-4)
            // Speed limits carried per segment: 50 before the junction, 30 after.
            assertEquals(50, route.maxspeedAtSegment(route.segmentAt(300.0)))
            assertEquals(30, route.maxspeedAtSegment(route.segmentAt(route.length - 200)))

            // Same answer as desktop GraphHopper with the Janino-compiled model.
            val desktop = GraphHopper().init(GraphSpec.config(dir.absolutePath)).also { it.load() }
            val ref = desktop.route(GHRequest(GHPoint(start.lat, start.lon), GHPoint(end.lat, end.lon)).setProfile(GraphSpec.PROFILE)).best
            desktop.close()
            assertEquals(ref.distance, route.length, 1.0)
            assertEquals(ref.time / 1000.0, route.durationS, 1.0)

            assertFailsWith<OfflineRoutingException> { g.route(listOf(start, GeoPoint(48.0, 24.0))) }
        }
        tmp.deleteRecursively()
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("gh").toFile()
}
