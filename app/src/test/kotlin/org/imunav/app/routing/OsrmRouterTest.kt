package org.imunav.app.routing

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.math.roundToLong

/** The online fallback router parses OSRM answers into the engine's route model and reports failures. */
@RunWith(RobolectricTestRunner::class)
class OsrmRouterTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun router() = OsrmRouter(server.url("/").toString().trimEnd('/'))

    /** Google's encoded polyline at 6 decimals, as OSRM sends it. */
    private fun encode(points: List<GeoPoint>): String = buildString {
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            for ((value, last) in listOf((p.lat * 1e6).roundToLong() to lastLat, (p.lon * 1e6).roundToLong() to lastLon)) {
                var delta = value - last
                delta = if (delta < 0) (delta shl 1).inv() else delta shl 1
                while (delta >= 0x20) {
                    append(((0x20 or (delta and 0x1f).toInt()) + 63).toChar())
                    delta = delta shr 5
                }
                append((delta.toInt() + 63).toChar())
            }
            lastLat = (p.lat * 1e6).roundToLong()
            lastLon = (p.lon * 1e6).roundToLong()
        }
    }

    private fun maneuver(type: String, at: GeoPoint, modifier: String? = null, exit: Int? = null) = JSONObject()
        .put("type", type).put("location", JSONArray().put(at.lon).put(at.lat))
        .apply {
            modifier?.let { put("modifier", it) }
            exit?.let { put("exit", it) }
        }

    private fun step(maneuver: JSONObject, name: String, ref: String = "") = JSONObject().put("maneuver", maneuver).put("name", name).put("ref", ref)
        .put("distance", 100.0).put("duration", 10.0)

    /** Two legs through a stop: start → stop → end, with speed annotations on every segment. */
    private fun twoLegAnswer(): String {
        val a = GeoPoint(50.0, 30.0)
        val b = GeoPoint(50.01, 30.0)
        val c = GeoPoint(50.01, 30.01)
        val leg1 = JSONObject().put("summary", "Main street")
            .put("steps", JSONArray().put(step(maneuver("depart", a), "Main street", "M06")).put(step(maneuver("arrive", b), "")))
            .put("annotation", JSONObject().put("speed", JSONArray().put(13.9)).put("maxspeed", JSONArray().put(JSONObject().put("speed", 50).put("unit", "km/h"))))
        val leg2 = JSONObject()
            .put("steps", JSONArray().put(step(maneuver("depart", b), "")).put(step(maneuver("roundabout", b, "right", exit = 2), "Ring")).put(step(maneuver("arrive", c), "")))
            .put("annotation", JSONObject().put("speed", JSONArray().put(8.3)).put("maxspeed", JSONArray().put(JSONObject().put("speed", 30).put("unit", "mph"))))
        val route = JSONObject().put("geometry", encode(listOf(a, b, c))).put("duration", 240.0).put("legs", JSONArray().put(leg1).put(leg2))
        return JSONObject().put("code", "Ok").put("routes", JSONArray().put(route)).toString()
    }

    @Test
    fun twoLegAnswerBecomesOneRouteWithOuterDepartAndArriveOnly() {
        server.enqueue(MockResponse.Builder().code(200).body(twoLegAnswer()).build())
        val route = runBlocking { router().route(GeoPoint(50.0, 30.0), GeoPoint(50.01, 30.01), listOf(GeoPoint(50.01, 30.0)), TravelMode.CAR) }
        val request = server.takeRequest()
        assertTrue(request.target.startsWith("/route/v1/driving/30.000000,50.000000;30.000000,50.010000;30.010000,50.010000"))

        assertEquals(3, route.geometry.size)
        assertEquals(listOf("depart", "roundabout", "arrive"), route.steps.map { it.type })
        assertEquals("Main street (M06)", route.steps.first().name)
        assertEquals(2, route.steps[1].roundaboutExit)
        assertEquals("right", route.steps[1].modifier)
        assertEquals(1, route.steps[1].geometryIndex)
        assertEquals(listOf(50, 48), route.maxspeedKmh)
        assertEquals(listOf(13.9, 8.3), route.segmentSpeedMps)
        assertEquals("Main street", route.summary)
        assertEquals(240.0, route.durationS, 0.0)
    }

    @Test
    fun unusableAnswersAndWalkingAreReportedAsIOExceptions() {
        server.enqueue(MockResponse.Builder().code(200).body("{\"code\":\"NoRoute\",\"message\":\"Impossible route\"}").build())
        val noRoute = assertThrows(IOException::class.java) { runBlocking { router().route(GeoPoint(50.0, 30.0), GeoPoint(51.0, 31.0)) } }
        assertTrue(noRoute.message!!.contains("NoRoute"))

        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        val down = assertThrows(IOException::class.java) { runBlocking { router().route(GeoPoint(50.0, 30.0), GeoPoint(51.0, 31.0)) } }
        assertTrue(down.message!!.startsWith("OSRM"))

        assertThrows(IOException::class.java) { runBlocking { router().route(GeoPoint(50.0, 30.0), GeoPoint(51.0, 31.0), mode = TravelMode.FOOT) } }
    }

    @Test
    fun annotationsThatDoNotMatchTheGeometryAreDropped() {
        val answer = JSONObject(twoLegAnswer())
        answer.getJSONArray("routes").getJSONObject(0).getJSONArray("legs").getJSONObject(1).remove("annotation")
        val route = OsrmRouter.parse(answer.toString())
        assertTrue(route.maxspeedKmh.isEmpty())
        assertTrue(route.segmentSpeedMps.isEmpty())
        assertNull(route.maxspeedAtSegment(0))
        assertEquals(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95)), OsrmRouter.decodePolyline("_p~iF~ps|U_ulLnnqC", 5))
    }
}
