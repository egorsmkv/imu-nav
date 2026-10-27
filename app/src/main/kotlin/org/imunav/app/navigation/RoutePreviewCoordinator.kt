package org.imunav.app.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.routing.Router
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.RoadPreference
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode

/** Inputs that make a preview reusable when navigation starts. */
internal data class RoutePreviewRequest(
    val from: GeoPoint,
    val destination: GeoPoint,
    val mode: TravelMode,
    val via: List<GeoPoint> = emptyList(),
    val preference: RoadPreference = RoadPreference.MAIN_ROADS,
    val packKey: String? = null,
)

/** Keeps only the latest route preview; an older routing result must never replace a newer one. */
internal class RoutePreviewCoordinator(private val scope: CoroutineScope, private val router: Router, private val geometryDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private var job: Job? = null
    private var generation = 0L
    private var request: RoutePreviewRequest? = null

    /** Cancel in-flight calculation while retaining the request for a possible cached route. */
    fun cancel() {
        job?.cancel()
        generation++
    }

    /** Forget the cached request after navigation takes ownership of the route. */
    fun clear() {
        cancel()
        request = null
    }

    /** Return the completed preview only when the endpoints, stops and travel mode still agree. */
    fun reusableRoute(candidate: Route?, requested: RoutePreviewRequest): Route? = candidate.takeIf { request == requested }

    /** Switching completed alternatives keeps the cards stable and makes startup reuse the selected route. */
    fun selectAlternative(preference: RoadPreference, alternatives: Map<RoadPreference, Route>, packKey: String): Route? {
        val current = request?.takeIf { it.mode == TravelMode.CAR && it.packKey == packKey } ?: return null
        val route = alternatives[preference] ?: return null
        cancel()
        request = current.copy(preference = preference)
        return route
    }

    /** Calculate a preview and publish only the result of the latest request. */
    fun update(
        from: GeoPoint?,
        destination: GeoPoint?,
        via: List<GeoPoint>,
        mode: TravelMode,
        publish: (PreviewResult) -> Unit,
        preference: RoadPreference = RoadPreference.MAIN_ROADS,
        packKey: String? = null,
        alternatives: Boolean = false,
    ) {
        cancel()
        if (from == null || destination == null) {
            request = null
            publish(PreviewResult.Cleared)
            return
        }
        val next = RoutePreviewRequest(from, destination, mode, via, preference, packKey)
        val currentGeneration = generation
        request = next
        publish(PreviewResult.Loading)
        job = scope.launch {
            runCatching { router.route(next.from, next.destination, next.via, next.mode, next.preference) }
                .onSuccess { route ->
                    if (currentGeneration != generation) return@onSuccess
                    publish(PreviewResult.Ready(route))
                    if (alternatives && mode == TravelMode.CAR) {
                        val choices = routeAlternatives(next, route)
                        if (choices.isNotEmpty() && currentGeneration == generation) publish(PreviewResult.Ready(route, choices))
                    }
                }
                .onFailure { error -> if (currentGeneration == generation) publish(PreviewResult.Failed(error.message)) }
        }
    }

    /** An optional alternative must never discard the selected route or hide cancellation. */
    private suspend fun routeAlternatives(next: RoutePreviewRequest, route: Route): Map<RoadPreference, Route> {
        val otherPreference = if (next.preference == RoadPreference.MAIN_ROADS) RoadPreference.BALANCED else RoadPreference.MAIN_ROADS
        val other = runCatching { router.route(next.from, next.destination, next.via, next.mode, otherPreference) }.getOrElse {
            if (it is CancellationException) throw it
            return emptyMap()
        }
        return withContext(geometryDispatcher) {
            if (route.geometry == other.geometry) emptyMap() else mapOf(next.preference to route, otherPreference to other)
        }
    }
}

/** A preview transition applied by the main-thread UI owner. */
internal sealed interface PreviewResult {
    data object Cleared : PreviewResult
    data object Loading : PreviewResult
    data class Ready(val route: Route, val alternatives: Map<RoadPreference, Route> = emptyMap()) : PreviewResult
    data class Failed(val message: String?) : PreviewResult
}
