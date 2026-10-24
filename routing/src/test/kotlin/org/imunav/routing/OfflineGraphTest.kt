package org.imunav.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.reader.dem.ElevationProvider
import com.graphhopper.util.shapes.GHPoint
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.ResultKind
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // A footpath cutting the corner diagonally: pedestrians may use it, cars may not.
        val path = (1..5).map { 4000L + it to (50.455 + it * 0.001 to 30.500 + it * 0.0016) }
        (north + east + stub + path).forEach { (id, p) -> sb.append("<node id='$id' lat='${p.first}' lon='${p.second}' version='1'/>\n") }
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
        way(4, listOf(1005L) + path.map { it.first } + listOf(2008L), "Parkpath", "footway", 5)
        sb.append("</osm>\n")
        file.writeText(sb.toString())
    }

    /** A fake terrain model: the land rises 10 m per 0.001° north and 2 m per 0.001° east. */
    private val slope = object : ElevationProvider {
        override fun getEle(lat: Double, lon: Double): Double = 100.0 + (lat - 50.450) * 10_000.0 + (lon - 30.500) * 2_000.0

        override fun canInterpolate(): Boolean = false

        override fun release() = Unit
    }

    @Test
    fun packWithElevationGivesRoutesAHeightProfile() {
        val tmp = createTempDirectory()
        val osm = File(tmp, "test.osm").also { writeOsm(it) }
        val dir = File(tmp, "graph3d")
        val info = buildGraph(osm, dir, "hills", minNetworkSize = 0, withSearch = false, elevation = PackElevation(custom = slope))
        assertTrue(info.elevation)
        assertTrue(PackInfo.parse(File(dir, PackInfo.FILE).readText())!!.elevation)
        OfflineGraph.load(dir).use { g ->
            assertTrue(g.hasElevation)
            val route = g.route(listOf(GeoPoint(50.4502, 30.5001), GeoPoint(50.4611, 30.5099)))
            val heights = assertNotNull(route.elevationM)
            assertEquals(route.geometry.size, heights.size)
            // Road points sit on the model (±1 m: heights are stored with limited precision). The snapped
            // start and end points get an approximate height from GraphHopper; only the first/last meters matter.
            for (i in 1 until route.geometry.lastIndex) assertEquals(slope.getEle(route.geometry[i].lat, route.geometry[i].lon), heights[i], 1.0)
            assertEquals(slope.getEle(route.geometry[0].lat, route.geometry[0].lon), heights[0], 30.0)
            // Halfway up Northway (≈ 600 m in) the road is ~60 m higher than at the start.
            assertEquals(160.0, assertNotNull(route.elevationAt(600.0)), 8.0)
        }
    }

    @Test
    fun packWithoutElevationStaysFlat() {
        val tmp = createTempDirectory()
        val osm = File(tmp, "test.osm").also { writeOsm(it) }
        val dir = File(tmp, "graph2d")
        assertFalse(buildGraph(osm, dir, "flat", minNetworkSize = 0, withSearch = false).elevation)
        OfflineGraph.load(dir).use { g ->
            assertFalse(g.hasElevation)
            assertNull(g.route(listOf(GeoPoint(50.4502, 30.5001), GeoPoint(50.4611, 30.5099))).elevationM)
        }
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
            assertTrue(info.turnCosts, "new packs are built with turn costs")
            val desktop = GraphHopper().init(GraphSpec.config(dir.absolutePath, turnCosts = true)).also { it.load() }
            val ref = desktop.route(GHRequest(GHPoint(start.lat, start.lon), GHPoint(end.lat, end.lon)).setProfile(GraphSpec.CAR)).best
            assertEquals(ref.distance, route.length, 1.0)
            assertEquals(ref.time / 1000.0, route.durationS, 1.0)
            assertTrue(route.steps.none { it.name == "Parkpath" }, "cars must not use the footway")

            // Walking takes the footway shortcut, and the phone agrees with desktop GraphHopper.
            assertTrue(g.supports(TravelMode.FOOT))
            val walk = g.route(listOf(start, end), TravelMode.FOOT)
            assertTrue(walk.steps.any { it.name == "Parkpath" }, "walking route uses the footway: ${walk.steps.map { it.name }}")
            assertTrue(walk.length < route.length - 300, "walk ${walk.length} vs drive ${route.length}")
            val refWalk = desktop.route(GHRequest(GHPoint(start.lat, start.lon), GHPoint(end.lat, end.lon)).setProfile(GraphSpec.FOOT)).best
            desktop.close()
            assertEquals(refWalk.distance, walk.length, 1.0)
            assertEquals(refWalk.time / 1000.0, walk.durationS, 1.0)
            assertTrue(walk.durationS > route.durationS * 3, "walking is much slower than driving")

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
                val town = AddressSearch.search(db, "тесто", near)
                assertEquals("Тестове", town.first().title, "prefix match on a settlement: $town")
                assertEquals("Тестове", AddressSearch.search(db, "Testove", near).first().title, "English name")
                val street = AddressSearch.search(db, "вул. Eastway", near)
                assertEquals("Eastway", street.first().title, "street-type word ignored: $street")
                assertEquals("Тестове", street.first().subtitle, "street attached to its settlement")
                val house = AddressSearch.search(db, "Eastway 5а", near)
                assertEquals(ResultKind.ADDRESS, house.first().kind, "house number found: $house")
                assertEquals(50.4612, house.first().point.lat, 1e-6)
                val combo = AddressSearch.search(db, "Тестове Northway 12", near)
                assertEquals("Northway, 12", combo.first().title, "settlement + street + number: $combo")
            }
            assertTrue(matched.geometry.all { abs(it.lon - 30.500) < 1e-4 || abs(it.lat - 50.461) < 1e-4 }, "matched points lie on the roads")
        }
        tmp.deleteRecursively()
    }

    @Test
    fun carOnlyPacksFromOlderVersionsStillLoad() {
        val tmp = createTempDirectory()
        val osm = File(tmp, "test.osm").also { writeOsm(it) }
        val dir = File(tmp, "graph")
        buildGraph(osm, dir, "old", minNetworkSize = 0, withSearch = false, profiles = listOf(GraphSpec.CAR), turnCosts = false)
        assertEquals(listOf(GraphSpec.CAR), PackInfo.parse(File(dir, PackInfo.FILE).readText())?.profiles)
        assertFalse(PackInfo.parse(File(dir, PackInfo.FILE).readText())!!.turnCosts)
        OfflineGraph.load(dir).use { g ->
            assertTrue(!g.supports(TravelMode.FOOT))
            val route = g.route(listOf(GeoPoint(50.4502, 30.5001), GeoPoint(50.4611, 30.5135)))
            assertTrue(route.length in 2000.0..2600.0)
            assertFailsWith<OfflineRoutingException> { g.route(listOf(GeoPoint(50.4502, 30.5001), GeoPoint(50.4611, 30.5135)), TravelMode.FOOT) }
        }
        // pack.json written before profiles existed means car-only, and before turn costs means none.
        val legacy = PackInfo.parse("{\"name\":\"x\",\"bounds\":[1,2,3,4],\"sizeBytes\":5}")
        assertEquals(listOf(GraphSpec.CAR), legacy?.profiles)
        assertFalse(legacy!!.turnCosts)
        tmp.deleteRecursively()
    }

    /**
     * A junction where Mainway continues north and Westway branches off to the left. Turning left is
     * forbidden (OSM restriction relation); the legal way round is north, then west and back south.
     */
    private fun writeRestrictedJunction(file: File) {
        val nodes = mapOf(
            1L to (50.450 to 30.500), // south end of Mainway
            2L to (50.455 to 30.500), // the junction
            3L to (50.460 to 30.500), // north end of Mainway
            4L to (50.455 to 30.495), // west end of Westway
            5L to (50.460 to 30.495), // north-west corner of the detour
        )
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<osm version='0.6'>\n")
        nodes.forEach { (id, p) -> sb.append("<node id='$id' lat='${p.first}' lon='${p.second}' version='1'/>\n") }
        fun way(id: Long, from: Long, to: Long, name: String) =
            sb.append("<way id='$id' version='1'><nd ref='$from'/><nd ref='$to'/><tag k='highway' v='secondary'/><tag k='name' v='$name'/></way>\n")
        way(11, 1, 2, "Mainway")
        way(12, 2, 3, "Mainway")
        way(13, 2, 4, "Westway")
        way(14, 3, 5, "Northloop")
        way(15, 5, 4, "Westloop")
        sb.append("<relation id='21' version='1'><member type='way' ref='11' role='from'/><member type='node' ref='2' role='via'/>")
        sb.append("<member type='way' ref='13' role='to'/><tag k='type' v='restriction'/><tag k='restriction' v='no_left_turn'/></relation>\n")
        sb.append("</osm>\n")
        file.writeText(sb.toString())
    }

    @Test
    fun turnRestrictionsAreObeyedOnPhoneAndDesktop() {
        val tmp = createTempDirectory()
        val osm = File(tmp, "junction.osm").also { writeRestrictedJunction(it) }
        val from = GeoPoint(50.4510, 30.5000)
        val to = GeoPoint(50.4550, 30.4960)

        // A pack built before turn costs takes the forbidden left turn: about 900 m.
        val oldDir = File(tmp, "old")
        buildGraph(osm, oldDir, "old", minNetworkSize = 0, withSearch = false, turnCosts = false)
        val forbidden = OfflineGraph.load(oldDir).use { it.route(listOf(from, to)) }
        assertTrue(forbidden.steps.any { it.name == "Westway" && it.type == "turn" && it.modifier == "left" }, "steps: ${forbidden.steps}")
        assertTrue(forbidden.length < 1000.0, "length ${forbidden.length}")

        // Current packs go round via Northloop and Westloop: the restriction holds on the phone path.
        val dir = File(tmp, "turns")
        assertTrue(buildGraph(osm, dir, "turns", minNetworkSize = 0, withSearch = false).turnCosts)
        OfflineGraph.load(dir).use { g ->
            val legal = g.route(listOf(from, to))
            // The corners have no other road, so GraphHopper emits no instruction there: check the shape instead.
            assertTrue(legal.geometry.any { abs(it.lat - 50.460) < 1e-5 && abs(it.lon - 30.495) < 1e-5 }, "detour via the north-west corner: ${legal.geometry}")
            assertTrue(legal.steps.none { it.name == "Westway" && it.modifier == "left" }, "no forbidden left turn: ${legal.steps}")
            assertTrue(legal.length > forbidden.length + 500.0, "legal ${legal.length} vs forbidden ${forbidden.length}")

            // Phone weighting (plain code) and desktop weighting (Janino) agree on routes with turns.
            val desktop = GraphHopper().init(GraphSpec.config(dir.absolutePath, turnCosts = true)).also { it.load() }
            for ((a, b) in listOf(from to to, to to from, GeoPoint(50.4595, 30.4955) to GeoPoint(50.4510, 30.5000))) {
                val phone = g.route(listOf(a, b))
                val ref = desktop.route(GHRequest(GHPoint(a.lat, a.lon), GHPoint(b.lat, b.lon)).setProfile(GraphSpec.CAR)).best
                assertEquals(ref.distance, phone.length, 1.0, "distance $a → $b")
                assertEquals(ref.time / 1000.0, phone.durationS, 1.0, "time $a → $b")
            }
            desktop.close()
        }
        tmp.deleteRecursively()
    }

    @Test
    fun turnPenaltiesFollowTheSharedTable() {
        // GraphHopper's change_angle: positive to the right, negative to the left.
        val penalty = { angle: Double -> GraphSpec.CAR_TURN_PENALTIES.filter { it.matches(angle) }.sumOf { it.seconds } }
        assertEquals(0.0, penalty(0.0))
        assertEquals(0.0, penalty(24.0))
        assertEquals(2.0, penalty(45.0))
        assertEquals(5.0, penalty(180.0))
        assertEquals(8.0, penalty(-45.0))
        assertEquals(12.0, penalty(-120.0))
        assertTrue(penalty(-90.0) > penalty(90.0), "left turns cross oncoming traffic and cost more")
        // The desktop statements describe exactly the same bands.
        assertEquals("change_angle <= -25.0 && change_angle > -80.0", GraphSpec.CAR_TURN_PENALTIES[2].statement().condition)
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("gh").toFile()
}
