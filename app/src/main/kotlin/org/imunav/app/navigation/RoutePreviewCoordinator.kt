package org.imunav.app.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.imunav.app.routing.Router
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode

/** Inputs that make a preview reusable when navigation starts. */
internal data class RoutePreviewRequest(val from: GeoPoint, val destination: GeoPoint, val mode: TravelMode)

/** Keeps only the latest route preview; an older routing result must never replace a newer one. */
internal class RoutePreviewCoordinator(private val scope: CoroutineScope, private val router: Router) {
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

    /** Return the completed preview only when both endpoints and travel mode still agree. */
    fun reusableRoute(candidate: Route?, requested: RoutePreviewRequest): Route? = candidate.takeIf { request == requested }

    /** Calculate a preview and publish only the result of the latest request. */
    fun update(from: GeoPoint?, destination: GeoPoint?, mode: TravelMode, publish: (PreviewResult) -> Unit) {
        cancel()
        if (from == null || destination == null) {
            request = null
            publish(PreviewResult.Cleared)
            return
        }
        val next = RoutePreviewRequest(from, destination, mode)
        val currentGeneration = generation
        request = next
        publish(PreviewResult.Loading)
        job = scope.launch {
            runCatching { router.route(next.from, next.destination, mode = next.mode) }
                .onSuccess { route -> if (currentGeneration == generation) publish(PreviewResult.Ready(route)) }
                .onFailure { error -> if (currentGeneration == generation) publish(PreviewResult.Failed(error.message)) }
        }
    }
}

/** A preview transition applied by the main-thread UI owner. */
internal sealed interface PreviewResult {
    data object Cleared : PreviewResult
    data object Loading : PreviewResult
    data class Ready(val route: Route) : PreviewResult
    data class Failed(val message: String?) : PreviewResult
}
