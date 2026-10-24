package org.imunav.core.search

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import java.util.Locale
import kotlin.math.ln

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

/** An address retains its street identity throughout a batched lookup. */
data class StreetAddressRow(val streetId: Long, val number: String, val lat: Double, val lon: Double)

/** Storage-agnostic access to a search index (legacy SQLite FTS4 or a request-local Tantivy snapshot). */
interface SearchDb {
    /** Places whose names match all FTS [terms] (already normalized, with prefix '*'). */
    fun places(match: String, limit: Int, near: GeoPoint? = null): List<PlaceRow>
    fun streets(match: String, limit: Int, placeIds: Collection<Long>? = null, near: GeoPoint? = null): List<StreetRow>
    fun addresses(streetId: Long, number: String): List<AddressRow>

    /** All exact/building-part matches; the caller ranks by distance before retaining two per street. */
    fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow>
}

/** Normalized words and settlement interpretations, shared with experimental candidate providers. */
data class SearchPlan(val words: List<String>, val number: String?) {
    val splits: List<Pair<List<String>, List<String>>> = buildList {
        words.indices.filter { words.size > 1 }.forEach { index -> add(listOf(words[index]) to words.filterIndexed { i, _ -> i != index }) }
        for (size in 2..minOf(3, words.size - 1)) {
            add(words.take(size) to words.drop(size))
            add(words.takeLast(size) to words.dropLast(size))
        }
    }.distinct()
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
    private val HOUSE = Regex("^\\d{1,4}\\p{L}?(/\\d{1,4}\\p{L}?)?$")

    private val PLAIN_TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val BUILDING_PART = Regex("[кk]\\d{1,4}")
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}'/]+")

    /** Lower-case, unify apostrophes, drop punctuation — the same function builds the index. */
    fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
        .replace('ё', 'е')
        .replace(PUNCTUATION, " ")
        .trim()

    private fun fts(tokens: List<String>) = tokens.joinToString(" ") { it.replace("'", "") + "*" }

    /** Search stages share request-local lookups and distances; cancellation is never converted to a miss. */
    fun search(db: SearchDb, query: String, near: GeoPoint?, limit: Int = 20, checkCancelled: () -> Unit = {}): List<SearchResult> {
        checkCancelled()
        val plan = prepare(query)
        return search(db, plan, near, limit, checkCancelled)
    }

    /** Prepared input lets experimental backends retrieve candidates in one native request. */
    fun prepare(query: String): SearchPlan {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() && it !in STOP }
        val numberWords = houseWordCount(tokens)
        val number = tokens.takeLast(numberWords).joinToString(" ").takeIf { it.isNotEmpty() }
        val words = tokens.dropLast(numberWords).filter { !HOUSE.matches(it) || it.length > 4 }
        return SearchPlan(words, number)
    }

    /** Shared ranking keeps storage experiments subject to the same address semantics. */
    fun search(db: SearchDb, plan: SearchPlan, near: GeoPoint?, limit: Int = 20, checkCancelled: () -> Unit = {}): List<SearchResult> {
        checkCancelled()
        val words = plan.words
        val number = plan.number
        if (words.isEmpty()) return emptyList()
        val placeCache = mutableMapOf<Pair<String, Int>, List<PlaceRow>>()
        val streetCache = mutableMapOf<Triple<String, Int, List<Long>>, List<StreetRow>>()
        fun places(parts: List<String>, count: Int): List<PlaceRow> {
            checkCancelled()
            val match = fts(parts)
            return placeCache.getOrPut(match to count) {
                provenPlaces(placeCache, parts, count) ?: db.places(match, count, near)
            }
        }
        fun streets(parts: List<String>, count: Int, ids: List<Long> = emptyList()): List<StreetRow> {
            checkCancelled()
            val key = Triple(fts(parts), count, ids.sorted())
            return streetCache.getOrPut(key) { db.streets(key.first, count, ids.takeIf { it.isNotEmpty() }, near) }
        }
        val places = places(words, 30)
        val candidates = streets(words, 80).associateByTo(linkedMapOf()) { it.id }
        val splits = plan.splits
        for ((townWords, streetWords) in splits) {
            val towns = places(townWords, 10).filter { it.kind in SETTLEMENTS }
            if (towns.isNotEmpty()) streets(streetWords, 40, towns.map { it.id }).forEach { candidates[it.id] = it }
        }
        val distances = mutableMapOf<GeoPoint, Double?>()
        fun distance(lat: Double, lon: Double): Double? = distances.getOrPut(GeoPoint(lat, lon)) { near?.let { Geo.distance(it.lat, it.lon, lat, lon) } }
        val ranked = candidates.values.sortedWith(compareBy<StreetRow> { distance(it.lat, it.lon) ?: 0.0 }.thenBy { it.id })
        val addresses = if (number == null || candidates.isEmpty()) {
            emptyList()
        } else {
            checkCancelled()
            val order = compareBy<StreetAddressRow> { it.number != number }.thenBy { distance(it.lat, it.lon) ?: 0.0 }
                .thenBy { it.streetId }.thenBy { it.number }.thenBy { it.lat }.thenBy { it.lon }
            val perStreet = mutableMapOf<Long, Int>()
            db.batchAddresses(candidates.keys, number).sortedWith(order)
                .filter { address ->
                    val count = perStreet.getOrDefault(address.streetId, 0)
                    perStreet[address.streetId] = count + 1
                    count < 2
                }.map { address ->
                    val street = candidates.getValue(address.streetId)
                    SearchResult(
                        ResultKind.ADDRESS,
                        "${street.name}, ${address.number}",
                        street.placeName.orEmpty(),
                        GeoPoint(address.lat, address.lon),
                        distance(address.lat, address.lon),
                    )
                }
        }
        checkCancelled()
        val normalizedWords = words.joinToString(" ")
        val out = places.map { place ->
            val d = distance(place.lat, place.lon)
            val weight = (KIND_WEIGHT[place.kind] ?: 1.0) * (1 + ln(1.0 + place.population / 1000.0))
            val exact = if (normalize(place.name) == normalizedWords) 4.0 else 1.0
            -(weight * exact * 1e6) / (1 + (d ?: 50_000.0) / 20_000.0) to
                SearchResult(ResultKind.PLACE, place.name, place.kind, GeoPoint(place.lat, place.lon), d)
        } + ranked.mapIndexed { index, street ->
            val d = distance(street.lat, street.lon)
            (d ?: index * 1000.0) to SearchResult(ResultKind.STREET, street.name, street.placeName.orEmpty(), GeoPoint(street.lat, street.lon), d)
        }
        return (addresses + out.sortedBy { it.first }.map { it.second }).distinctBy { it.kind to it.title to it.subtitle }.take(limit)
    }

    /** Keep an explicit building part with its number instead of interpreting it as a street word. */
    private fun houseWordCount(tokens: List<String>): Int = when {
        tokens.size >= 3 && BUILDING_PART.matches(tokens.last()) && HOUSE.matches(tokens[tokens.lastIndex - 1]) -> 2
        tokens.size >= 2 && HOUSE.matches(tokens.last()) -> 1
        else -> 0
    }

    /** A narrower conjunction keeps the same nearest rows if every leading superset row provably matches. */
    private fun provenPlaces(cache: Map<Pair<String, Int>, List<PlaceRow>>, parts: List<String>, count: Int): List<PlaceRow>? {
        if (!parts.all(PLAIN_TOKEN::matches)) return null
        return cache.entries.firstOrNull { (key, rows) ->
            key.second >= count && parts.containsAll(key.first.split(' ').map { it.removeSuffix("*") }) &&
                rows.take(count).all { visibleNameMatches(it.name, parts) }
        }?.value?.take(count)
    }

    /** Visible names can prove a positive FTS match; unknown aliases cannot prove a negative one. */
    private fun visibleNameMatches(name: String, parts: List<String>): Boolean {
        val words = normalize(name).replace("'", "").split(' ', '/')
        return parts.all { prefix -> words.any { it.startsWith(prefix) } }
    }

    val SETTLEMENTS = setOf("city", "town", "village", "hamlet")
    private val KIND_WEIGHT = mapOf("city" to 50.0, "town" to 12.0, "village" to 3.0, "suburb" to 2.5, "hamlet" to 1.0, "neighbourhood" to 1.0, "quarter" to 1.5)
}

/**
 * Address of a Photon geocoder (https://github.com/komoot/photon), the online search fallback.
 * The public server is used by default; anyone can self-host Photon (it serves `/api` on port 2322).
 */
object PhotonServer {
    const val DEFAULT_URL = "https://photon.komoot.io/api"

    /**
     * Turn what the user typed into the API endpoint URL, or null if it is not a usable URL.
     *
     * Accepts a bare host or the full endpoint: `photon.example.org`, `http://10.0.0.5:2322`,
     * `https://photon.example.org/api/` all work. Blank text means "use the default server".
     */
    fun normalize(text: String): String? {
        var url = text.trim()
        if (url.isEmpty()) return DEFAULT_URL
        if ("://" !in url) url = "https://$url"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val host = url.substringAfter("://").substringBefore('/')
        if (host.isBlank() || ' ' in url || '?' in url) return null
        url = url.trimEnd('/')
        return if (url.endsWith("/api")) url else "$url/api"
    }
}

/** Results belong to both the query and its geographic reference; stale batches cannot be selected. */
data class SearchBatch(val query: String, val near: GeoPoint?, val results: List<SearchResult>)
