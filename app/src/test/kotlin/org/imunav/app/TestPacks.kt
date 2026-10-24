package org.imunav.app

import org.imunav.core.geo.GeoPoint
import org.imunav.routing.buildGraph
import org.imunav.routing.zipPack
import java.io.File

/**
 * A tiny offline routing pack for host tests: a 1.1 km road north, then 1.4 km east, with a side
 * street at the junction. Built with the real pack builder, so the app imports and loads it as usual.
 */
internal object TestPacks {
    val START = GeoPoint(50.4502, 30.5001)
    val TURN = GeoPoint(50.461, 30.500)
    val DESTINATION = GeoPoint(50.4611, 30.5135)

    /** Build the pack ZIP in [dir] (car + foot, no address index) and return it. */
    fun build(dir: File): File {
        dir.mkdirs()
        val osm = File(dir, "test.osm")
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<osm version='0.6'>\n")
        val north = (0..11).map { 1000L + it to (50.450 + it * 0.001 to 30.500) }
        val east = (1..14).map { 2000L + it to (50.461 to 30.500 + it * 0.001) }
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
        osm.writeText(sb.toString())
        val graph = File(dir, "graph")
        buildGraph(osm, graph, "Test", minNetworkSize = 0, withSearch = false)
        return File(dir, "graph.zip").also { zipPack(graph, it) }
    }
}
