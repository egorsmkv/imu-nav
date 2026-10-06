package org.imunav.app.trips

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.imunav.app.sync.SyncApi
import org.imunav.core.record.TripPlaybackReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Creates a bounded, versioned playback document without raw GPS, logs or device settings. */
internal suspend fun playbackDocument(file: File, trip: TripSummary): JSONObject = withContext(Dispatchers.IO) {
    val context = currentCoroutineContext()
    val playback = file.inputStream().use { TripPlaybackReader.read(it, context::ensureActive) }
    val positions = JSONArray()
    playback.positions.forEach {
        context.ensureActive()
        positions.put(
            JSONObject().put("time_ms", it.timeMs).put("segment", it.segment).put("lat", it.point.lat).put("lon", it.point.lon)
                .put("uncertainty_m", it.uncertaintyM).put("source", it.source),
        )
    }
    val routes = JSONArray()
    playback.routes.forEach { route ->
        val points = JSONArray()
        route.points.forEach { points.put(JSONArray().put(it.lon).put(it.lat)) }
        routes.put(JSONObject().put("time_ms", route.timeMs).put("segment", route.segment).put("points", points))
    }
    JSONObject().put("version", 1).put("incomplete", playback.incomplete)
        .put("positions", positions).put("routes", routes)
        .put(
            "summary",
            JSONObject().put("start_ms", trip.startWallMs).put("end_ms", trip.endWallMs).put("mode", trip.mode.name)
                .put("arrived", trip.arrived).put("distance_m", trip.drivenM).put("duration_s", trip.durationS).put("moving_s", trip.movingS)
                .put("blind_s", trip.blindS).put("blind_m", trip.blindM).put("max_uncertainty_m", trip.maxUncertaintyM)
                .put("route_length_m", trip.routeLengthM).put("reroutes", trip.reroutes),
        )
}

/** Uses account credentials only in Authorization headers; server IDs are stable for safe retries. */
internal class TripArchiveClient(url: String, token: String) {
    private val api = SyncApi(url, token)
    suspend fun metadata(): JSONObject = api.request("/v1/trips")
    suspend fun consent(version: String) {
        api.request("/v1/privacy/consents/trip_archive", "PUT", JSONObject().put("notice_version", version))
    }
    suspend fun upload(id: String, document: JSONObject, maxBytes: Int) {
        withContext(Dispatchers.IO) {
            require(document.toString().toByteArray(Charsets.UTF_8).size <= maxBytes) { "Playback too large" }
            api.request("/v1/trips/$id", "PUT", document)
        }
    }
}
