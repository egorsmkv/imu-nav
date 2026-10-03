package org.imunav.app.search

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Preserves the existing preference format while salvaging valid rows from damaged history. */
object RecentSearchCodec {
    /** Non-object and unreadable rows are skipped individually, including JSON null entries. */
    fun decode(text: String?): List<SearchResult> {
        val array = try {
            JSONArray(text ?: "[]")
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::decodeRow) }
    }

    fun encode(results: List<SearchResult>): String = JSONArray().apply {
        results.forEach { result ->
            put(
                JSONObject().put("k", result.kind.name).put("t", result.title).put("s", result.subtitle)
                    .put("lat", result.point.lat).put("lon", result.point.lon),
            )
        }
    }.toString()

    private fun decodeRow(row: JSONObject): SearchResult? = try {
        SearchResult(
            kind = ResultKind.valueOf(row.getString("k")),
            title = row.getString("t"),
            subtitle = row.optString("s"),
            point = GeoPoint(row.getDouble("lat"), row.getDouble("lon")),
            distanceM = null,
            source = "recent",
        )
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
