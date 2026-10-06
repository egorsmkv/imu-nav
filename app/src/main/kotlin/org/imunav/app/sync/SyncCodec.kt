package org.imunav.app.sync

import org.imunav.core.bookmarks.Bookmark
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.imunav.core.sync.SyncEntry
import org.json.JSONArray
import org.json.JSONObject

/** Explicit wire types keep credentials and transient route geometry out of account data. */
internal object SyncCodec {
    fun point(point: GeoPoint): JSONObject = JSONObject().put("lat", point.lat).put("lon", point.lon)
    fun point(value: JSONObject): GeoPoint = GeoPoint(value.getDouble("lat"), value.getDouble("lon")).also {
        require(it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0)
    }
    private fun endpoint(value: BookmarkPoint): JSONObject = JSONObject().put("point", point(value.point)).put("label", value.label ?: JSONObject.NULL)
    private fun endpoint(value: JSONObject): BookmarkPoint = BookmarkPoint(point(value.getJSONObject("point")), if (value.isNull("label")) null else value.getString("label"))

    fun bookmark(value: Bookmark): String = canonical(
        JSONObject().put("name", value.name).apply {
            when (value) {
                is SavedPlace -> put("type", "place").put("endpoint", endpoint(value.endpoint))

                is SavedRoute -> put("type", "route").put("destination", endpoint(value.destination)).put("mode", value.mode.name)
                    .put("origin", (value.origin as? BookmarkOrigin.Fixed)?.let { endpoint(it.endpoint) } ?: JSONObject.NULL)
            }
        },
    )

    fun bookmark(id: String, text: String): Bookmark {
        val value = JSONObject(text)
        return when (value.getString("type")) {
            "place" -> SavedPlace(id, value.getString("name"), endpoint(value.getJSONObject("endpoint")))

            "route" -> SavedRoute(
                id,
                value.getString("name"),
                if (value.isNull("origin")) BookmarkOrigin.Automatic else BookmarkOrigin.Fixed(endpoint(value.getJSONObject("origin"))),
                endpoint(value.getJSONObject("destination")),
                TravelMode.valueOf(value.getString("mode")),
            )

            else -> error("Unsupported bookmark")
        }
    }

    fun entries(array: JSONArray): List<SyncEntry> = (0 until array.length()).map { index ->
        val entry = array.getJSONObject(index)
        val kind = entry.getString("kind")
        val key = entry.getString("key")
        val revision = entry.getLong("revision")
        require(kind in setOf("setting", "bookmark") && key.isNotBlank() && revision >= 0)
        SyncEntry(kind, key, revision, if (entry.isNull("value")) null else canonical(entry.get("value")))
    }

    fun entries(entries: List<SyncEntry>): JSONArray = JSONArray().apply {
        entries.forEach { put(JSONObject().put("kind", it.kind).put("key", it.key).put("revision", it.revision).put("value", it.payload?.let(::parse) ?: JSONObject.NULL)) }
    }

    fun parse(text: String): Any = JSONArray("[$text]").get(0)

    /** Stable object key order makes equality independent of JSON serializers and database backend. */
    fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
