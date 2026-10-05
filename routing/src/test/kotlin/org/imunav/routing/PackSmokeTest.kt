package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.imunav.core.search.AddressSearch
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Long routes on a real pack through the phone code path. Runs only when GRAPH_DIR points at a pack:
 *   GRAPH_DIR=graph-ukraine ./gradlew :routing:test --tests '*PackSmokeTest*'
 */
class PackSmokeTest {
    @Test
    fun packagedGraphLoadsWithPhoneRules() {
        val dir = System.getenv("GRAPH_DIR")?.let(::File)
        assumeTrue("GRAPH_DIR not set", dir != null && File(dir, "properties").exists())
        OfflineGraph.load(dir!!).use { graph ->
            assertTrue(graph.supports(TravelMode.CAR))
            assertTrue(graph.supports(TravelMode.FOOT))
        }
        JdbcSearchDb(File(dir, SearchIndexBuilder.FILE)).use { search ->
            search.places("zzzz", 1) // An empty lookup still checks the imported FTS schema.
        }
    }

    @Test
    fun longRoutesOnRealPack() {
        val dir = System.getenv("GRAPH_DIR")?.let(::File)
        assumeTrue("GRAPH_DIR not set", dir != null && File(dir, "properties").exists())
        val t0 = System.nanoTime()
        OfflineGraph.load(dir!!).use { g ->
            println("load ${(System.nanoTime() - t0) / 1_000_000} ms")
            val kyiv = GeoPoint(50.4501, 30.5234)
            for ((name, dest, km) in listOf(
                Triple("Lviv", GeoPoint(49.8397, 24.0297), 500.0..600.0),
                Triple("Odesa", GeoPoint(46.4825, 30.7233), 440.0..520.0),
                Triple("Kharkiv", GeoPoint(49.9935, 36.2304), 440.0..520.0),
            )) {
                val t = System.nanoTime()
                val r = g.route(listOf(kyiv, dest))
                val ms = (System.nanoTime() - t) / 1_000_000
                val limits = r.maxspeedKmh.count { it != null }
                println(
                    "Kyiv→$name: ${(r.length / 1000).toInt()} km, ${(r.durationS / 3600 * 10).toInt() / 10.0} h, ${r.steps.size} steps, $limits/${r.maxspeedKmh.size} segments with limit, $ms ms",
                )
                assertTrue(r.length / 1000 in km, "$name length ${r.length / 1000} km")
            }
        }
    }

    @Test
    fun walkingRoutesOnRealPack() {
        val dir = System.getenv("GRAPH_DIR")?.let(::File)
        assumeTrue("GRAPH_DIR not set", dir != null && File(dir, "properties").exists())
        OfflineGraph.load(dir!!).use { g ->
            assumeTrue("pack has no walking data", g.supports(TravelMode.FOOT))
            // Odesa centre: pedestrians cut across Cathedral Square and pedestrian streets; cars must go round.
            val from = GeoPoint(46.48445, 30.73180)
            val to = GeoPoint(46.48510, 30.74000)
            val walk = g.route(listOf(from, to), TravelMode.FOOT)
            val drive = g.route(listOf(from, to), TravelMode.CAR)
            val walkNames = walk.steps.map { it.name }.distinct()
            println(
                "walk ${walk.length.toInt()} m ${(walk.durationS / 60).toInt()} min via $walkNames; drive ${drive.length.toInt()} m via ${drive.steps.map {
                    it.name
                }.distinct()}",
            )
            assertTrue(walk.length < drive.length - 200, "walk ${walk.length} m should be clearly shorter than drive ${drive.length} m")
            val pace = walk.length / walk.durationS
            assertTrue(pace in 0.9..1.8, "walking pace $pace m/s")
            // A longer walk across central Kyiv (Khreshchatyk → Kyiv Pechersk Lavra).
            val long = g.route(listOf(GeoPoint(50.4501, 30.5234), GeoPoint(50.4346, 30.5573)), TravelMode.FOOT)
            println("Kyiv walk ${long.length.toInt()} m, ${(long.durationS / 60).toInt()} min, ${long.steps.size} steps")
            assertTrue(long.length in 2500.0..6000.0, "Kyiv walk ${long.length}")
        }
    }

    @Test
    fun addressSearchOnRealPack() {
        val dir = System.getenv("GRAPH_DIR")?.let(::File)
        assumeTrue("GRAPH_DIR not set", dir != null && File(dir, SearchIndexBuilder.FILE).exists())
        val kyiv = GeoPoint(50.4501, 30.5234)
        JdbcSearchDb(File(dir!!, SearchIndexBuilder.FILE)).use { db ->
            for ((q, near) in listOf(
                "Хрещатик 22" to kyiv,
                "Львів" to kyiv,
                "буча" to kyiv,
                "Шевченка Львів" to kyiv,
                "Київ Грушевського 5" to kyiv,
                "Одеса Дерибасівська" to kyiv,
                "Kharkiv" to kyiv,
                "вул. Велика Васильківська 100" to kyiv,
            )) {
                val t = System.nanoTime()
                val r = AddressSearch.search(db, q, near, limit = 3)
                val ms = (System.nanoTime() - t) / 1_000_000
                println("'$q' ($ms ms): " + r.joinToString(" | ") { "${it.kind} ${it.title} [${it.subtitle}] ${it.distanceM?.let { d -> (d / 1000).toInt() }}km" })
                assertTrue(r.isNotEmpty(), "no results for $q")
            }
        }
    }
}
