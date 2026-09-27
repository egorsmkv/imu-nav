package org.blinddriver.app.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.Step
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

interface Router {
    suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint> = emptyList()): Route
}

/**
 * OSRM HTTP client (defaults to the public demo server — fine for testing, run your own for real
 * use). Requests full polyline6 geometry, turn-by-turn steps and per-segment annotations
 * (speed limits when the server has them, otherwise modelled speeds).
 */
class OsrmRouter(private val baseUrl: String = "https://router.project-osrm.org") : Router {

    override suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint>): Route = withContext(Dispatchers.IO) {
        val coords = (listOf(from) + via + to).joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val url = "$baseUrl/route/v1/driving/$coords?overview=full&geometries=polyline6&steps=true&annotations=true"
        parse(fetch(url))
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "blind-driver-opensource/0.1")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("OSRM HTTP $code: ${body.take(200)}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parse(json: String): Route {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") throw IOException("OSRM: ${root.optString("code")} ${root.optString("message")}")
            val r = root.getJSONArray("routes").getJSONObject(0)
            val geometry = decodePolyline(r.getString("geometry"), 6)
            val steps = ArrayList<Step>()
            val maxspeed = ArrayList<Int?>()
            val segmentSpeed = ArrayList<Double?>()
            var searchFrom = 0
            val legs = r.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val leg = legs.getJSONObject(l)
                val legSteps = leg.getJSONArray("steps")
                for (i in 0 until legSteps.length()) {
                    val st = legSteps.getJSONObject(i)
                    val m = st.getJSONObject("maneuver")
                    val type = m.getString("type")
                    // Intermediate legs start with "depart" and end with "arrive" at waypoints; keep only the outer ones.
                    if (type == "depart" && l > 0) continue
                    if (type == "arrive" && l < legs.length() - 1) continue
                    val loc = m.getJSONArray("location")
                    val point = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                    val idx = nearestIndex(geometry, point, searchFrom)
                    searchFrom = idx
                    steps += Step(
                        type = type,
                        modifier = m.optString("modifier").ifEmpty { null },
                        name = st.optString("ref").ifEmpty { st.optString("name") }.let { ref ->
                            val name = st.optString("name")
                            if (ref.isNotEmpty() && name.isNotEmpty() && ref != name) "$name ($ref)" else ref
                        },
                        distanceM = st.optDouble("distance", 0.0),
                        durationS = st.optDouble("duration", 0.0),
                        geometryIndex = idx,
                        roundaboutExit = if (m.has("exit")) m.getInt("exit") else null,
                    )
                }
                leg.optJSONObject("annotation")?.optJSONArray("speed")?.let { arr ->
                    for (i in 0 until arr.length()) segmentSpeed += arr.optDouble(i).takeIf { !it.isNaN() }
                }
                leg.optJSONObject("annotation")?.optJSONArray("maxspeed")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i)
                        val speed = o?.optInt("speed", -1) ?: -1
                        val unit = o?.optString("unit").orEmpty()
                        maxspeed += when {
                            speed <= 0 -> null
                            unit == "mph" -> (speed * 1.609).toInt()
                            else -> speed
                        }
                    }
                }
            }
            if (maxspeed.size != geometry.size - 1) maxspeed.clear()
            if (segmentSpeed.size != geometry.size - 1) segmentSpeed.clear()
            return Route(
                geometry = geometry,
                steps = steps,
                durationS = r.optDouble("duration", 0.0),
                maxspeedKmh = maxspeed,
                summary = legs.optJSONObject(0)?.optString("summary").orEmpty(),
                segmentSpeedMps = segmentSpeed,
            )
        }

        private fun nearestIndex(geometry: List<GeoPoint>, p: GeoPoint, from: Int): Int {
            var best = from
            var bestD = Double.MAX_VALUE
            for (i in from until geometry.size) {
                val d = Geo.distance(geometry[i], p)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
                if (bestD < 1.0) break
            }
            return best
        }

        fun decodePolyline(encoded: String, precision: Int): List<GeoPoint> {
            val factor = Math.pow(10.0, precision.toDouble())
            val out = ArrayList<GeoPoint>()
            var index = 0
            var lat = 0L
            var lon = 0L
            while (index < encoded.length) {
                for (axis in 0..1) {
                    var result = 0L
                    var shift = 0
                    var b: Int
                    do {
                        b = encoded[index++].code - 63
                        result = result or ((b and 0x1f).toLong() shl shift)
                        shift += 5
                    } while (b >= 0x20)
                    val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                    if (axis == 0) lat += delta else lon += delta
                }
                out += GeoPoint(lat / factor, lon / factor)
            }
            return out
        }
    }
}
