package org.imunav.app.search

import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.net.Http
import org.imunav.core.search.AddressRow
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.PhotonServer
import org.imunav.core.search.PlaceRow
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchDb
import org.imunav.core.search.SearchOrder
import org.imunav.core.search.SearchResult
import org.imunav.core.search.StreetAddressRow
import org.imunav.core.search.StreetRow
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only access to a routing pack's `search.db` (built by `SearchIndexBuilder` on a computer).
 * Implements the storage-independent [SearchDb] interface that [AddressSearch] queries.
 */
class AndroidSearchDb private constructor(private val db: SQLiteDatabase, private val signal: CancellationSignal?) :
    SearchDb,
    AutoCloseable {
    constructor(file: File) : this(SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS), null)

    /** A request view shares the locked connection but never shares cancellation with another request. */
    fun search(query: String, near: GeoPoint?, cancellation: CancellationSignal): List<SearchResult> =
        AddressSearch.search(AndroidSearchDb(db, cancellation), query, near, checkCancelled = cancellation::throwIfCanceled)

    override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> {
        val order = SearchOrder.near("p", near)
        return query(
            "SELECT p.id, p.name, p.kind, p.lat, p.lon, p.population FROM place_fts f JOIN place p ON p.id = f.rowid WHERE place_fts MATCH ?${order.sql} LIMIT ?",
            *(listOf(match) + order.arguments + limit.toString()).toTypedArray(),
        ) { PlaceRow(id = getLong(0), name = getString(1), kind = getString(2), lat = getDouble(3), lon = getDouble(4), population = getInt(5)) }
    }

    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?, near: GeoPoint?): List<StreetRow> {
        val order = SearchOrder.near("s", near)
        // The ids are numbers we produced ourselves, so putting them into the SQL is safe.
        val inPlaces = if (placeIds.isNullOrEmpty()) "" else " AND s.place_id IN (${placeIds.joinToString(",")})"
        return query(
            "SELECT s.id, s.name, s.place_id, p.name, s.lat, s.lon FROM street_fts f JOIN street s ON s.id = f.rowid " +
                "LEFT JOIN place p ON p.id = s.place_id WHERE street_fts MATCH ?$inPlaces${order.sql} LIMIT ?",
            *(listOf(match) + order.arguments + limit.toString()).toTypedArray(),
        ) {
            StreetRow(
                id = getLong(0),
                name = getString(1),
                placeId = if (isNull(2)) null else getLong(2),
                placeName = getString(3),
                lat = getDouble(4),
                lon = getDouble(5),
            )
        }
    }

    override fun addresses(streetId: Long, number: String): List<AddressRow> = query(
        // "22" also finds "22 к1" (building parts), but not "220".
        "SELECT number, lat, lon FROM addr WHERE street_id = ? AND (number = ? OR number LIKE ?) LIMIT 5",
        streetId.toString(),
        number,
        "$number %",
    ) { AddressRow(number = getString(0), lat = getDouble(1), lon = getDouble(2)) }

    override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> {
        if (streetIds.isEmpty()) return emptyList()
        return query(
            "SELECT street_id,number,lat,lon FROM addr WHERE street_id IN (${streetIds.joinToString(",")}) AND (number=? OR number LIKE ?)",
            number,
            "$number %",
        ) { StreetAddressRow(getLong(0), getString(1), getDouble(2), getDouble(3)) }
    }

    override fun close() = db.close()

    /** Run [sql] and turn every row into a [T] with [readRow] (called with the cursor as `this`). */
    private fun <T> query(sql: String, vararg args: String, readRow: Cursor.() -> T): List<T> = db.rawQuery(sql, args, signal).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.readRow())
        }
    }
}

/**
 * Address and place search for the search screen.
 *
 * 1. The offline index from the routing pack is asked first (works without internet).
 * 2. Only if it finds nothing, and the user allowed online services, Photon (a free OpenStreetMap
 *    geocoder) is asked.
 *
 * It also remembers the last places the user picked ("recent").
 */
class PlaceSearch(
    context: Context,
    private val offlineSearch: (String, GeoPoint?, CancellationSignal) -> List<SearchResult>,
    private val offlineAvailable: () -> Boolean,
    private val allowOnline: () -> Boolean,
) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    private val _photonUrl = MutableStateFlow(prefs.getString(KEY_PHOTON_URL, null) ?: PhotonServer.DEFAULT_URL)

    /** The Photon endpoint used for online search (Settings → Address search). */
    val photonUrl: StateFlow<String> = _photonUrl.asStateFlow()

    /**
     * Use the Photon server at [text] (a host or full URL; blank = the public default).
     * @return false if [text] is not a usable URL (nothing is changed then)
     */
    fun setPhotonUrl(text: String): Boolean {
        val url = PhotonServer.normalize(text) ?: return false
        prefs.edit { if (url == PhotonServer.DEFAULT_URL) remove(KEY_PHOTON_URL) else putString(KEY_PHOTON_URL, url) }
        _photonUrl.value = url
        return true
    }

    /**
     * Check that [text] points at a working Photon server by searching for "Київ".
     * @return the number of results, or the error (network, HTTP status, not a Photon answer)
     */
    suspend fun testPhoton(text: String): Result<Int> = withContext(Dispatchers.IO) {
        val url = PhotonServer.normalize(text) ?: return@withContext Result.failure(IllegalArgumentException("invalid URL"))
        try {
            Result.success(photon(TEST_QUERY, near = null, endpoint = url).size)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: JSONException) {
            Result.failure(IOException("not a Photon server", e))
        }
    }

    /** Is an offline index installed? */
    val hasOffline: Boolean get() = offlineAvailable()

    /** Search for [query]; results near [near] rank first. Safe to call from the main thread. */
    suspend fun search(query: String, near: GeoPoint?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.trim().length < MIN_QUERY_LENGTH) return@withContext emptyList()
        val offline = searchOffline(query, near)
        currentCoroutineContext().ensureActive()
        if (offline.isNotEmpty() || !allowOnline()) return@withContext offline
        searchOnline(query, near)
    }

    /** Search the pack's index; empty on errors. */
    private suspend fun searchOffline(query: String, near: GeoPoint?): List<SearchResult> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            signal.throwIfCanceled()
            continuation.resume(offlineSearch(query, near, signal))
        } catch (e: SQLException) {
            // Never log search text or a database exception that may embed it.
            Log.w(TAG, "offline search failed type=${e.javaClass.simpleName}")
            continuation.resume(emptyList())
        } catch (e: IOException) {
            Log.w(TAG, "offline search failed type=${e.javaClass.simpleName}")
            continuation.resume(emptyList())
        } catch (e: CancellationException) {
            continuation.cancel(e)
        } catch (e: OperationCanceledException) {
            continuation.cancel(CancellationException("Offline search cancelled", e))
        }
    }

    /** Ask Photon; empty when offline or the server fails. */
    private suspend fun searchOnline(query: String, near: GeoPoint?): List<SearchResult> = try {
        photon(query, near)
    } catch (e: IOException) {
        Log.w(TAG, "online search failed type=${e.javaClass.simpleName}") // no network, server down…
        emptyList()
    } catch (e: JSONException) {
        Log.w(TAG, "unexpected Photon answer type=${e.javaClass.simpleName}")
        emptyList()
    }

    private val history = RecentSearches(
        load = { RecentSearchCodec.decode(prefs.getString(KEY_RECENT, "[]")) },
        save = { values -> prefs.edit { putString(KEY_RECENT, RecentSearchCodec.encode(values)) } },
        onFailure = { Log.w(TAG, "recent_search_failed", it) },
    )

    /** Shared immutable history, loaded and persisted off the UI thread. */
    val recent: StateFlow<List<SearchResult>> = history.state

    /** Enqueue a pick from either display without blocking or a read-modify-write race. */
    fun remember(result: SearchResult) = history.remember(result)

    // ------------------------------------------------------------------ online fallback (Photon)

    /** Ask the configured Photon server ([endpoint]); see https://photon.komoot.io for the API. Blocking. */
    private suspend fun photon(query: String, near: GeoPoint?, endpoint: String = photonUrl.value): List<SearchResult> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", MAX_ONLINE_RESULTS.toString())
            .apply {
                // Location bias: prefer results near the user.
                if (near != null) {
                    addQueryParameter("lat", String.format(Locale.US, "%.5f", near.lat))
                    addQueryParameter("lon", String.format(Locale.US, "%.5f", near.lon))
                }
            }
            .build()
        currentCoroutineContext().ensureActive()
        val body = suspendCancellableCoroutine { continuation ->
            val call = Http.client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Photon HTTP ${response.code}")
                    continuation.resume(response.body.string())
                }
            } catch (e: IOException) {
                continuation.resumeWithException(e)
            }
        }
        currentCoroutineContext().ensureActive()
        val features = JSONObject(body).getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            currentCoroutineContext().ensureActive()
            photonResult(features.getJSONObject(i), near)
        }
    }

    /** One GeoJSON feature from Photon → a [SearchResult], or null if it has nothing to show. */
    private fun photonResult(feature: JSONObject, near: GeoPoint?): SearchResult? {
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates") // [lon, lat]
        val props = feature.getJSONObject("properties")
        val point = GeoPoint(lat = coordinates.getDouble(1), lon = coordinates.getDouble(0))
        val street = props.optString("street")
        val number = props.optString("housenumber")
        val name = props.optString("name")
        val city = props.optString("city").ifEmpty { props.optString("county") }
        val (kind, title) = when {
            number.isNotEmpty() && street.isNotEmpty() -> ResultKind.ADDRESS to "$street, $number"
            props.optString("osm_key") == "place" -> ResultKind.PLACE to name
            name.isNotEmpty() -> ResultKind.STREET to name
            else -> return null
        }
        return SearchResult(kind, title, city, point, near?.let { Geo.distance(it, point) }, source = "online")
    }

    private companion object {
        const val TAG = "PlaceSearch"
        const val KEY_PHOTON_URL = "photon_url"
        const val TEST_QUERY = "Київ"
        const val KEY_RECENT = "recent"
        const val MIN_QUERY_LENGTH = 2
        const val MAX_ONLINE_RESULTS = 10
    }
}
