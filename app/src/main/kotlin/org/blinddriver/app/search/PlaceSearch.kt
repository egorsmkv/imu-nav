package org.blinddriver.app.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.search.AddressRow
import org.blinddriver.core.search.AddressSearch
import org.blinddriver.core.search.PlaceRow
import org.blinddriver.core.search.ResultKind
import org.blinddriver.core.search.SearchDb
import org.blinddriver.core.search.SearchResult
import org.blinddriver.core.search.StreetRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Read-only SQLite access to a pack's `search.db`. */
class AndroidSearchDb(file: File) :
    SearchDb,
    AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    override fun places(match: String, limit: Int): List<PlaceRow> = db.rawQuery(
        "SELECT p.id,p.name,p.kind,p.lat,p.lon,p.population FROM place_fts f JOIN place p ON p.id=f.rowid WHERE place_fts MATCH ? LIMIT ?",
        arrayOf(match, limit.toString()),
    ).use { c ->
        generateSequence { if (c.moveToNext()) PlaceRow(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4), c.getInt(5)) else null }.toList()
    }

    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?): List<StreetRow> {
        val filter = placeIds?.takeIf { it.isNotEmpty() }?.let { " AND s.place_id IN (${it.joinToString(",")})" }.orEmpty()
        return db.rawQuery(
            "SELECT s.id,s.name,s.place_id,p.name,s.lat,s.lon FROM street_fts f JOIN street s ON s.id=f.rowid LEFT JOIN place p ON p.id=s.place_id " +
                "WHERE street_fts MATCH ?$filter LIMIT ?",
            arrayOf(match, limit.toString()),
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) StreetRow(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3), c.getDouble(4), c.getDouble(5)) else null
            }.toList()
        }
    }

    override fun addresses(streetId: Long, number: String): List<AddressRow> = db.rawQuery(
        "SELECT number,lat,lon FROM addr WHERE street_id=? AND (number=? OR number LIKE ?) LIMIT 5",
        arrayOf(streetId.toString(), number, "$number %"),
    ).use { c -> generateSequence { if (c.moveToNext()) AddressRow(c.getString(0), c.getDouble(1), c.getDouble(2)) else null }.toList() }

    override fun close() = db.close()
}

/**
 * Address / place search: the offline index from the routing pack first; Photon (OpenStreetMap
 * geocoder) online when allowed and the offline index has nothing. Keeps recent picks.
 */
class PlaceSearch(context: Context, private val offlineDb: () -> SearchDb?, private val allowOnline: () -> Boolean) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    suspend fun search(query: String, near: GeoPoint?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val offline = offlineDb()?.let { db -> runCatching { AddressSearch.search(db, query, near) }.getOrDefault(emptyList()) }.orEmpty()
        if (offline.isNotEmpty() || !allowOnline()) return@withContext offline
        runCatching { photon(query, near) }.getOrDefault(emptyList())
    }

    val hasOffline: Boolean get() = offlineDb() != null

    // ------------------------------------------------------------------ recent picks

    fun recent(): List<SearchResult> = runCatching {
        val a = JSONArray(prefs.getString("recent", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SearchResult(ResultKind.valueOf(o.getString("k")), o.getString("t"), o.optString("s"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")), null, "recent")
        }
    }.getOrDefault(emptyList())

    fun remember(r: SearchResult) {
        val list = (listOf(r) + recent()).distinctBy { it.title to it.subtitle }.take(10)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("k", it.kind.name).put("t", it.title).put("s", it.subtitle).put("lat", it.point.lat).put("lon", it.point.lon)) }
        prefs.edit { putString("recent", a.toString()) }
    }

    // ------------------------------------------------------------------ online fallback

    private fun photon(query: String, near: GeoPoint?): List<SearchResult> {
        val bias = near?.let { String.format(Locale.US, "&lat=%.5f&lon=%.5f", it.lat, it.lon) }.orEmpty()
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(query, "UTF-8")}&limit=10$bias"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "blind-driver-opensource/0.1")
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val features = JSONObject(body).getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val point = GeoPoint(c.getDouble(1), c.getDouble(0))
            val street = p.optString("street")
            val number = p.optString("housenumber")
            val name = p.optString("name")
            val city = p.optString("city").ifEmpty { p.optString("county") }
            val (kind, title) = when {
                number.isNotEmpty() && street.isNotEmpty() -> ResultKind.ADDRESS to "$street, $number"
                p.optString("osm_key") == "place" -> ResultKind.PLACE to name
                name.isNotEmpty() -> ResultKind.STREET to name
                else -> return@mapNotNull null
            }
            SearchResult(kind, title, city, point, near?.let { Geo.distance(it, point) }, "online")
        }
    }
}
