package org.blinddriver.core.search

import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import java.util.Locale

enum class ResultKind { PLACE, STREET, ADDRESS }

data class SearchResult(
    val kind: ResultKind,
    val title: String,
    /** Settlement / kind, e.g. "Київ" or "village". */
    val subtitle: String,
    val point: GeoPoint,
    val distanceM: Double?,
    /** Where it came from: "offline" or "online". */
    val source: String = "offline",
)

data class PlaceRow(val id: Long, val name: String, val kind: String, val lat: Double, val lon: Double, val population: Int)
data class StreetRow(val id: Long, val name: String, val placeId: Long?, val placeName: String?, val lat: Double, val lon: Double)
data class AddressRow(val number: String, val lat: Double, val lon: Double)

/** Storage-agnostic access to a search index (SQLite FTS4 on Android and on the desktop). */
interface SearchDb {
    /** Places whose names match all FTS [terms] (already normalized, with prefix '*'). */
    fun places(match: String, limit: Int): List<PlaceRow>
    fun streets(match: String, limit: Int, placeIds: Collection<Long>? = null): List<StreetRow>
    fun addresses(streetId: Long, number: String): List<AddressRow>
}

/**
 * Offline address search: "Хрещатик 22", "Київ Хрещатик", "вул. Шевченка, Львів", "Буча".
 * Street-type words are ignored, a trailing house number is looked up among the matched street's
 * addresses, and a token that names a settlement narrows streets to it.
 */
object AddressSearch {
    private val STOP = setOf(
        "вулиця", "вул", "проспект", "просп", "пр", "провулок", "пров", "площа", "пл", "бульвар", "бул", "б-р", "шосе",
        "узвіз", "набережна", "наб", "майдан", "тупик", "проїзд", "алея", "місто", "м", "село", "с", "смт", "селище",
        "улица", "ул", "street", "st", "avenue", "ave", "square", "город", "г",
    )
    private val HOUSE = Regex("^\\d{1,4}[\\p{L}]?(/\\d{1,4}[\\p{L}]?)?$")

    /** Lower-case, unify apostrophes, drop punctuation — the same function builds the index. */
    fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}'/]+"), " ")
        .trim()

    private fun fts(tokens: List<String>) = tokens.joinToString(" ") { it.replace("'", "") + "*" }

    fun search(db: SearchDb, query: String, near: GeoPoint?, limit: Int = 20): List<SearchResult> {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() && it !in STOP }
        if (tokens.isEmpty()) return emptyList()
        val number = tokens.lastOrNull()?.takeIf { tokens.size >= 2 && HOUSE.matches(it) }
        val words = (if (number != null) tokens.dropLast(1) else tokens).filter { !HOUSE.matches(it) || it.length > 4 }
        if (words.isEmpty()) return emptyList()

        val places = runCatching { db.places(fts(words), 30) }.getOrDefault(emptyList())
        var streets = runCatching { db.streets(fts(words), 80) }.getOrDefault(emptyList())
        // "Київ Хрещатик": one token names the settlement, the rest the street.
        if (words.size >= 2) {
            for (w in words) {
                val towns = runCatching { db.places(fts(listOf(w)), 10) }.getOrDefault(emptyList())
                    .filter { it.kind in SETTLEMENTS }
                if (towns.isEmpty()) continue
                val rest = words - w
                val inTown = runCatching { db.streets(fts(rest), 40, towns.map { it.id }) }.getOrDefault(emptyList())
                streets = (inTown + streets).distinctBy { it.id }
            }
        }

        fun dist(lat: Double, lon: Double) = near?.let { Geo.distance(it.lat, it.lon, lat, lon) }
        val out = ArrayList<Pair<Double, SearchResult>>()

        // House numbers on the best-matching streets come first.
        if (number != null) {
            for (st in rankStreets(streets, near).take(5)) {
                val hits = runCatching { db.addresses(st.id, number) }.getOrDefault(emptyList())
                for (a in hits.take(2)) {
                    out += -1e9 + (dist(a.lat, a.lon) ?: 0.0) to SearchResult(
                        ResultKind.ADDRESS,
                        "${st.name}, ${a.number}",
                        st.placeName.orEmpty(),
                        GeoPoint(a.lat, a.lon),
                        dist(a.lat, a.lon),
                    )
                }
            }
        }
        for (p in places) {
            val d = dist(p.lat, p.lon)
            val weight = (KIND_WEIGHT[p.kind] ?: 1.0) * (1 + kotlin.math.ln(1.0 + p.population / 1000.0))
            val exact = if (normalize(p.name) == words.joinToString(" ")) 4.0 else 1.0
            out += -(weight * exact * 1e6) / (1 + (d ?: 50_000.0) / 20_000.0) to
                SearchResult(ResultKind.PLACE, p.name, p.kind, GeoPoint(p.lat, p.lon), d)
        }
        for ((i, s) in rankStreets(streets, near).withIndex()) {
            val d = dist(s.lat, s.lon)
            out += (d ?: (i * 1000.0)) to SearchResult(ResultKind.STREET, s.name, s.placeName.orEmpty(), GeoPoint(s.lat, s.lon), d)
        }
        return out.sortedBy { it.first }.map { it.second }.distinctBy { it.kind to it.title to it.subtitle }.take(limit)
    }

    private fun rankStreets(streets: List<StreetRow>, near: GeoPoint?): List<StreetRow> =
        if (near == null) streets else streets.sortedBy { Geo.distance(near.lat, near.lon, it.lat, it.lon) }

    val SETTLEMENTS = setOf("city", "town", "village", "hamlet")
    private val KIND_WEIGHT = mapOf("city" to 50.0, "town" to 12.0, "village" to 3.0, "suburb" to 2.5, "hamlet" to 1.0, "neighbourhood" to 1.0, "quarter" to 1.5)
}
