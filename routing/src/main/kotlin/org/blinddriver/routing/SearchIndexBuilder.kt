package org.blinddriver.routing

import com.carrotsearch.hppc.LongHashSet
import com.carrotsearch.hppc.LongObjectHashMap
import com.graphhopper.GraphHopper
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMInputFile
import com.graphhopper.reader.osm.SkipOptions
import com.graphhopper.util.FetchMode
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.search.AddressRow
import org.blinddriver.core.search.AddressSearch
import org.blinddriver.core.search.PlaceRow
import org.blinddriver.core.search.SearchDb
import org.blinddriver.core.search.StreetRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.floor

/**
 * Builds `search.db` (SQLite FTS4, simple tokenizer over pre-normalized text) next to the graph:
 * places from OSM place nodes, streets from the graph's named edges (grouped per settlement), and
 * optionally house numbers from address nodes and buildings.
 */
object SearchIndexBuilder {
    const val FILE = "search.db"
    private val PLACE_KINDS = setOf("city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood")

    /** Settlement "reach" used to attach streets: a city claims streets much farther away than a hamlet. */
    private val REACH_M = mapOf("city" to 9_000.0, "town" to 3_500.0, "village" to 1_500.0, "hamlet" to 700.0)

    private class Place(val id: Long, val name: String, val nameEn: String?, val alt: String, val kind: String, val lat: Double, val lon: Double, val pop: Int)
    private class Addr(val number: String, val street: String, val lat: Double, val lon: Double)

    /** Street accumulated from named graph edges: centroid plus candidate midpoints. */
    private class StreetAcc(val name: String, val placeId: Long?) {
        var sumLat = 0.0
        var sumLon = 0.0
        var n = 0
        var length = 0.0
        val mids = ArrayList<DoubleArray>()
    }

    fun build(osm: File, hopper: GraphHopper, out: File, withAddresses: Boolean, log: (String) -> Unit = ::println) {
        out.delete()
        val (places, addrs) = readPlacesAndAddresses(osm, withAddresses, log)
        val settlements = SettlementIndex(places.filter { it.kind in REACH_M })
        val streets = collectStreets(hopper, settlements)
        log("[search] ${streets.size} streets")

        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${out.absolutePath}").use { c ->
            c.autoCommit = false
            createSchema(c)
            writePlaces(c, places)
            val byName = writeStreets(c, streets.values)
            if (addrs.isNotEmpty()) log("[search] ${writeAddresses(c, addrs, byName)} addresses attached to streets")
            c.createStatement().use { st ->
                st.executeUpdate("INSERT INTO meta VALUES ('version','1')")
                st.executeUpdate("INSERT INTO place_fts(place_fts) VALUES('optimize')")
                st.executeUpdate("INSERT INTO street_fts(street_fts) VALUES('optimize')")
            }
            c.commit()
            c.autoCommit = true
            c.createStatement().use { it.executeUpdate("VACUUM") }
        }
        log("[search] ${out.length() / 1_048_576} MB → ${out.name}")
    }

    /**
     * Pass 1 (ways only): buildings with addresses → remember their first node for a position.
     * Pass 2 (nodes only): places, address nodes, positions of those building nodes.
     */
    private fun readPlacesAndAddresses(osm: File, withAddresses: Boolean, log: (String) -> Unit): Pair<List<Place>, List<Addr>> {
        val wayAddrFirstNode = LongObjectHashMap<Pair<String, String>>()
        if (withAddresses) {
            readOsm(osm, SkipOptions(true, false, true)) { e ->
                if (e is ReaderWay) {
                    val num = e.getTag("addr:housenumber", null as String?)
                    val street = e.getTag("addr:street", null as String?)
                    if (num != null && street != null && !e.nodes.isEmpty) wayAddrFirstNode.put(e.nodes.get(0), num to street)
                }
            }
            log("[search] ${wayAddrFirstNode.size()} building addresses")
        }
        val places = ArrayList<Place>()
        val addrs = ArrayList<Addr>()
        readOsm(osm, SkipOptions(false, true, true)) { e ->
            if (e !is ReaderNode) return@readOsm
            placeOf(e, places.size + 1L)?.let { places += it }
            if (withAddresses) {
                val num = e.getTag("addr:housenumber", null as String?)
                val street = e.getTag("addr:street", null as String?)
                if (num != null && street != null) addrs += Addr(num, street, e.lat, e.lon)
                wayAddrFirstNode.get(e.id)?.let { (n, s) -> addrs += Addr(n, s, e.lat, e.lon) }
            }
        }
        log("[search] ${places.size} places, ${addrs.size} addresses")
        return places to addrs
    }

    private fun placeOf(e: ReaderNode, id: Long): Place? {
        val place = e.getTag("place", null as String?)
        val name = e.getTag("name", null as String?)
        if (place == null || place !in PLACE_KINDS || name == null) return null
        fun tag(k: String) = e.getTag(k, null as String?)
        val alt = listOfNotNull(tag("name:uk"), tag("name:en"), tag("name:ru"), tag("old_name"), tag("alt_name")).joinToString(" ")
        val population = tag("population")?.filter { it.isDigit() }?.take(9)?.toIntOrNull() ?: 0
        return Place(id, name, tag("name:en"), alt, place, e.lat, e.lon, population)
    }

    /** Settlement lookup on a ~5.5 km grid; a city claims streets much farther away than a hamlet. */
    private class SettlementIndex(settlements: List<Place>) {
        private val grid = HashMap<Long, MutableList<Place>>()

        init {
            settlements.forEach { grid.getOrPut(key(cellOf(it.lat), cellOf(it.lon))) { ArrayList() } += it }
        }

        private fun cellOf(deg: Double) = floor(deg / 0.05).toLong()

        private fun key(cy: Long, cx: Long) = cy * 100_000 + cx

        fun settlementOf(lat: Double, lon: Double): Place? {
            var best: Place? = null
            var score = Double.MAX_VALUE
            val r = 3 // ±3 cells ≈ ±16 km
            val cy = cellOf(lat)
            val cx = cellOf(lon)
            for (dy in -r..r) {
                for (dx in -r..r) {
                    for (p in grid[key(cy + dy, cx + dx)].orEmpty()) {
                        val s = Geo.distance(lat, lon, p.lat, p.lon) / (REACH_M[p.kind] ?: 1000.0)
                        if (s < score) {
                            score = s
                            best = p
                        }
                    }
                }
            }
            return if (score <= 2.5) best else null
        }
    }

    /** Streets: named edges grouped by (normalized name, settlement). */
    private fun collectStreets(hopper: GraphHopper, settlements: SettlementIndex): Map<String, StreetAcc> {
        val streets = HashMap<String, StreetAcc>()
        val it = hopper.baseGraph.allEdges
        while (it.next()) {
            val name = it.name?.trim().orEmpty()
            if (name.isEmpty()) continue
            val pts = it.fetchWayGeometry(FetchMode.ALL)
            val mid = pts.size() / 2
            val lat = pts.getLat(mid)
            val lon = pts.getLon(mid)
            val place = settlements.settlementOf(lat, lon)
            val key = AddressSearch.normalize(name) + "|" + (place?.id ?: 0)
            val acc = streets.getOrPut(key) { StreetAcc(name, place?.id) }
            acc.sumLat += lat
            acc.sumLon += lon
            acc.n++
            acc.length += it.distance
            acc.mids += doubleArrayOf(lat, lon)
        }
        return streets
    }

    private fun createSchema(c: Connection) = c.createStatement().use { st ->
        st.executeUpdate("CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, name_en TEXT, kind TEXT, lat REAL, lon REAL, population INTEGER)")
        st.executeUpdate("CREATE VIRTUAL TABLE place_fts USING fts4(names)")
        st.executeUpdate("CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT, place_id INTEGER, lat REAL, lon REAL, length REAL)")
        st.executeUpdate("CREATE VIRTUAL TABLE street_fts USING fts4(name)")
        st.executeUpdate("CREATE TABLE addr(street_id INTEGER, number TEXT, lat REAL, lon REAL)")
        st.executeUpdate("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    }

    private fun writePlaces(c: Connection, places: List<Place>) {
        c.prepareStatement("INSERT INTO place VALUES (?,?,?,?,?,?,?)").use { ps ->
            c.prepareStatement("INSERT INTO place_fts(rowid, names) VALUES (?,?)").use { fts ->
                for (p in places) {
                    ps.setLong(1, p.id)
                    ps.setString(2, p.name)
                    ps.setString(3, p.nameEn)
                    ps.setString(4, p.kind)
                    ps.setDouble(5, p.lat)
                    ps.setDouble(6, p.lon)
                    ps.setInt(7, p.pop)
                    ps.addBatch()
                    fts.setLong(1, p.id)
                    fts.setString(2, AddressSearch.normalize(p.name + " " + p.alt).replace("'", ""))
                    fts.addBatch()
                }
                ps.executeBatch()
                fts.executeBatch()
            }
        }
    }

    /**
     * Each street is stored at the edge midpoint nearest its centroid.
     * @return normalized name → (street id, lat, lon), for attaching addresses
     */
    private fun writeStreets(c: Connection, streets: Collection<StreetAcc>): Map<String, List<Triple<Long, Double, Double>>> {
        val byName = HashMap<String, MutableList<Triple<Long, Double, Double>>>()
        c.prepareStatement("INSERT INTO street VALUES (?,?,?,?,?,?)").use { ps ->
            c.prepareStatement("INSERT INTO street_fts(rowid, name) VALUES (?,?)").use { fts ->
                var id = 0L
                for (a in streets) {
                    id++
                    val cLat = a.sumLat / a.n
                    val cLon = a.sumLon / a.n
                    val rep = a.mids.minBy { Geo.distance(cLat, cLon, it[0], it[1]) }
                    ps.setLong(1, id)
                    ps.setString(2, a.name)
                    if (a.placeId != null) ps.setLong(3, a.placeId) else ps.setNull(3, java.sql.Types.INTEGER)
                    ps.setDouble(4, rep[0])
                    ps.setDouble(5, rep[1])
                    ps.setDouble(6, a.length)
                    ps.addBatch()
                    val norm = AddressSearch.normalize(a.name)
                    fts.setLong(1, id)
                    fts.setString(2, norm.replace("'", ""))
                    fts.addBatch()
                    byName.getOrPut(norm) { ArrayList() } += Triple(id, rep[0], rep[1])
                    if (id % 50_000 == 0L) {
                        ps.executeBatch()
                        fts.executeBatch()
                    }
                }
                ps.executeBatch()
                fts.executeBatch()
            }
        }
        return byName
    }

    /** Attach each address to the nearest street of the same name (within 8 km). @return addresses stored */
    private fun writeAddresses(c: Connection, addrs: List<Addr>, byName: Map<String, List<Triple<Long, Double, Double>>>): Int {
        var matched = 0
        c.prepareStatement("INSERT INTO addr VALUES (?,?,?,?)").use { ps ->
            for (a in addrs) {
                val candidates = byName[AddressSearch.normalize(a.street)] ?: continue
                val best = candidates.minBy { Geo.distance(a.lat, a.lon, it.second, it.third) }
                if (Geo.distance(a.lat, a.lon, best.second, best.third) > 8_000) continue
                ps.setLong(1, best.first)
                ps.setString(2, AddressSearch.normalize(a.number))
                ps.setDouble(3, a.lat)
                ps.setDouble(4, a.lon)
                ps.addBatch()
                matched++
                if (matched % 100_000 == 0) ps.executeBatch()
            }
            ps.executeBatch()
        }
        c.createStatement().use { it.executeUpdate("CREATE INDEX addr_street ON addr(street_id, number)") }
        return matched
    }

    private fun readOsm(osm: File, skip: SkipOptions, onElement: (ReaderElement) -> Unit) {
        val input = OSMInputFile(osm).setWorkerThreads(2).setSkipOptions(skip).open()
        try {
            while (true) {
                val e = input.next ?: break
                onElement(e)
            }
        } finally {
            input.close()
        }
    }
}

/** JDBC implementation of [SearchDb] (desktop tools and tests). */
class JdbcSearchDb(file: File) :
    SearchDb,
    AutoCloseable {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    override fun places(match: String, limit: Int): List<PlaceRow> =
        c.prepareStatement("SELECT p.id,p.name,p.kind,p.lat,p.lon,p.population FROM place_fts f JOIN place p ON p.id=f.rowid WHERE place_fts MATCH ? LIMIT ?").use { ps ->
            ps.setString(1, match)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) PlaceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6)) else null }.toList()
            }
        }

    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?): List<StreetRow> {
        val filter = placeIds?.takeIf { it.isNotEmpty() }?.let { " AND s.place_id IN (${it.joinToString(",")})" }.orEmpty()
        return c.prepareStatement(
            "SELECT s.id,s.name,s.place_id,p.name,s.lat,s.lon FROM street_fts f JOIN street s ON s.id=f.rowid LEFT JOIN place p ON p.id=s.place_id " +
                "WHERE street_fts MATCH ?$filter LIMIT ?",
        ).use { ps ->
            ps.setString(1, match)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) StreetRow(rs.getLong(1), rs.getString(2), rs.getLong(3).takeIf { !rs.wasNull() }, rs.getString(4), rs.getDouble(5), rs.getDouble(6)) else null
                }.toList()
            }
        }
    }

    override fun addresses(streetId: Long, number: String): List<AddressRow> =
        c.prepareStatement("SELECT number,lat,lon FROM addr WHERE street_id=? AND (number=? OR number LIKE ?) LIMIT 5").use { ps ->
            ps.setLong(1, streetId)
            ps.setString(2, number)
            ps.setString(3, "$number %")
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) AddressRow(rs.getString(1), rs.getDouble(2), rs.getDouble(3)) else null }.toList() }
        }

    override fun close() = c.close()
}
