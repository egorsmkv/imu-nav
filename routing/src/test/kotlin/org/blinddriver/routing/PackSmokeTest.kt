package org.blinddriver.routing

import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.search.AddressSearch
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
