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
        // A town node and house numbers for the search index.
        sb.append(
            "<node id='9001' lat='50.455' lon='30.505' version='1'><tag k='place' v='town'/><tag k='name' v='Тестове'/><tag k='name:en' v='Testove'/><tag k='population' v='12000'/></node>\n",
        )
        sb.append("<node id='9002' lat='50.4612' lon='30.5061' version='1'><tag k='addr:housenumber' v='5А'/><tag k='addr:street' v='Eastway'/></node>\n")
        sb.append("<node id='9003' lat='50.4555' lon='30.4999' version='1'><tag k='addr:housenumber' v='12'/><tag k='addr:street' v='Northway'/></node>\n")
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

            // Map matching: noisy GPS along the drive snaps back onto the two roads.
            val rnd = kotlin.random.Random(4)
            val noisy = (0..20).map { i ->
                val p = if (i <= 10) GeoPoint(50.451 + i * 0.001, 30.500) else GeoPoint(50.461, 30.500 + (i - 10) * 0.001)
                GeoPoint(p.lat + rnd.nextDouble(-0.00012, 0.00012), p.lon + rnd.nextDouble(-0.00018, 0.00018))
            }
            val matched = g.mapMatch(noisy, accuracyM = 15.0)
            assertTrue(matched.lengthM in 1700.0..2000.0, "matched length ${matched.lengthM}")
            // Offline search index built next to the graph.
            JdbcSearchDb(File(dir, SearchIndexBuilder.FILE)).use { db ->
                val near = GeoPoint(50.455, 30.505)
                val town = org.blinddriver.core.search.AddressSearch.search(db, "тесто", near)
                assertEquals("Тестове", town.first().title, "prefix match on a settlement: $town")
                assertEquals("Тестове", org.blinddriver.core.search.AddressSearch.search(db, "Testove", near).first().title, "English name")
                val street = org.blinddriver.core.search.AddressSearch.search(db, "вул. Eastway", near)
                assertEquals("Eastway", street.first().title, "street-type word ignored: $street")
                assertEquals("Тестове", street.first().subtitle, "street attached to its settlement")
                val house = org.blinddriver.core.search.AddressSearch.search(db, "Eastway 5а", near)
                assertEquals(org.blinddriver.core.search.ResultKind.ADDRESS, house.first().kind, "house number found: $house")
                assertEquals(50.4612, house.first().point.lat, 1e-6)
                val combo = org.blinddriver.core.search.AddressSearch.search(db, "Тестове Northway 12", near)
                assertEquals("Northway, 12", combo.first().title, "settlement + street + number: $combo")
            }
            assertTrue(matched.geometry.all { kotlin.math.abs(it.lon - 30.500) < 1e-4 || kotlin.math.abs(it.lat - 50.461) < 1e-4 }, "matched points lie on the roads")
        }
        tmp.deleteRecursively()
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("gh").toFile()
}
