package org.imunav.routing

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
import org.imunav.core.search.SearchIndexJson
import java.io.File
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** JNI owns opaque handles, never Java-visible pointers; Rust retains active requests during close. */
object RoutingNative {
    init {
        System.loadLibrary("imu_nav_routing")
    }
    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun begin(): Long
    external fun cancel(request: Long)
    external fun finish(request: Long)
    external fun route(handle: Long, request: Long, profile: String, json: String): String
    external fun mapMatch(handle: Long, request: Long, profile: String, json: String): String

    /** Available in host libraries only. */
    external fun build(input: String, output: String, name: String, minNetwork: Int, search: Boolean, addresses: Boolean)
}

/** Each task has its own cancellation flag; cancel never invalidates another request. */
class RoutingRequest : AutoCloseable {
    internal val handle = RoutingNative.begin()
    fun cancel() = RoutingNative.cancel(handle)
    override fun close() = RoutingNative.finish(handle)
}

class OfflineRoutingException(message: String) : Exception(message)
data class MatchedTrack(val geometry: List<GeoPoint>, val lengthM: Double, val rawLengthM: Double)

/** Immutable bounds remain safe to inspect after replacing or closing a pack. */
data class GraphCoverage(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    fun covers(point: GeoPoint): Boolean = point.lat in minLat - MARGIN_DEG..maxLat + MARGIN_DEG && point.lon in minLon - MARGIN_DEG..maxLon + MARGIN_DEG
    private companion object {
        const val MARGIN_DEG = 0.02
    }
}

/** Read-only Rust routing pack; each profile memory-maps its independently prepared hierarchy. */
class OfflineGraph private constructor(val dir: File, private val info: PackInfo, private val handle: Long) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private var closed = false
    val profiles = info.profiles
    val hasElevation = info.elevation
    val bounds = info.bounds.copyOf()
    val coverage = GraphCoverage(bounds[0], bounds[1], bounds[2], bounds[3])
    fun supports(mode: TravelMode): Boolean = mode.profile in profiles
    fun covers(point: GeoPoint): Boolean = coverage.covers(point)

    /** Locale is retained for source compatibility; maneuver vocabulary is translated by the app. */
    fun route(points: List<GeoPoint>, mode: TravelMode = TravelMode.CAR, locale: Locale = Locale.getDefault(), request: RoutingRequest? = null): Route = lock.read {
        // Native maneuvers use language-independent types; the existing UI localizes them.
        locale.toLanguageTag()
        check(!closed) { "Routing pack closed" }
        if (points.any { !covers(it) }) throw OfflineRoutingException("point outside offline map")
        if (!supports(mode)) throw OfflineRoutingException("profile unavailable")
        val payload = SearchIndexJson.encode(mapOf("points" to points.map { listOf(it.lat, it.lon) }))
        val json = if (request !=
            null
        ) {
            RoutingNative.route(handle, request.handle, mode.profile, payload)
        } else {
            RoutingRequest().use { RoutingNative.route(handle, it.handle, mode.profile, payload) }
        }
        decodeRoute(SearchIndexJson.decode(json))
    }

    /** Viterbi road matching uses the native graph and retains request-scoped cancellation. */
    fun mapMatch(points: List<GeoPoint>, accuracyM: Double = 20.0, mode: TravelMode = TravelMode.CAR, request: RoutingRequest? = null): MatchedTrack = lock.read {
        check(!closed) { "Routing pack closed" }
        if (!supports(mode)) throw OfflineRoutingException("profile unavailable")
        val payload = SearchIndexJson.encode(mapOf("points" to points.map { listOf(it.lat, it.lon) }, "accuracy" to accuracyM))
        val json = if (request !=
            null
        ) {
            RoutingNative.mapMatch(handle, request.handle, mode.profile, payload)
        } else {
            RoutingRequest().use { RoutingNative.mapMatch(handle, it.handle, mode.profile, payload) }
        }
        val route = decodeRoute(SearchIndexJson.decode(json))
        MatchedTrack(route.geometry, route.length, points.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) })
    }

    override fun close() = lock.write {
        if (!closed) RoutingNative.close(handle)
        closed = true
    }

    companion object {
        /** Legacy GraphHopper packs are explicitly rejected; no legacy engine is shipped. */
        fun load(dir: File): OfflineGraph {
            val metadata = File(dir, PackInfo.FILE)
            require(metadata.length() <= 65_536) { "Routing metadata too large" }
            val info = PackInfo.parse(metadata.readText()) ?: throw OfflineRoutingException("invalid routing metadata")
            if (info.routingFormat != PackInfo.FORMAT) throw OfflineRoutingException("New Rust routing pack required; rebuild or download a current pack")
            return OfflineGraph(dir, info, RoutingNative.open(dir.absolutePath))
        }

        private fun decodeRoute(data: Map<String, Any?>): Route {
            fun numbers(name: String) = (data[name] as? List<*>)?.map { it as? Number }.orEmpty()
            val geometry = (data["geometry"] as List<*>).map { item ->
                val pair = item as List<*>
                GeoPoint((pair[0] as Number).toDouble(), (pair[1] as Number).toDouble())
            }
            val steps = (data["steps"] as List<*>).map { item ->
                val step = item as Map<*, *>
                Step(
                    step["type"] as String,
                    step["modifier"] as? String,
                    step["name"] as String,
                    (step["distanceM"] as Number).toDouble(),
                    (step["durationS"] as Number).toDouble(),
                    (step["geometryIndex"] as Number).toInt(),
                    (step["roundaboutExit"] as? Number)?.toInt(),
                )
            }
            return Route(
                geometry,
                steps,
                (data["durationS"] as Number).toDouble(),
                numbers("maxspeedKmh").map { it?.toInt() },
                summary = "offline",
                segmentSpeedMps = numbers("segmentSpeedMps").map {
                    it?.toDouble()
                },
                elevationM = numbers("elevationM").takeIf { it.isNotEmpty() }?.map { requireNotNull(it).toDouble() }?.toDoubleArray(),
            )
        }
    }
}
