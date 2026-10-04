package org.imunav.app.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.imunav.app.routing.Router
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class RoutePreviewCoordinatorTest {
    @Test
    fun lateRouteCannotReplaceLatestPreviewAndOnlyMatchingRequestCanReuseIt() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pending = mutableListOf<Continuation<Route>>()
        val coordinator = RoutePreviewCoordinator(scope, pendingRouter(pending))
        val origin = GeoPoint(50.0, 30.0)
        val oldDestination = GeoPoint(50.01, 30.01)
        val destination = GeoPoint(50.02, 30.02)
        val events = mutableListOf<PreviewResult>()
        val oldRoute = route(origin, oldDestination)
        val latestRoute = route(origin, destination)

        coordinator.update(origin, oldDestination, TravelMode.CAR, events::add)
        dispatcher.drain()
        coordinator.update(origin, destination, TravelMode.CAR, events::add)
        dispatcher.drain()
        pending[0].resume(oldRoute)
        dispatcher.drain()
        assertEquals(listOf(PreviewResult.Loading, PreviewResult.Loading), events)

        pending[1].resume(latestRoute)
        dispatcher.drain()
        assertEquals(listOf(PreviewResult.Loading, PreviewResult.Loading, PreviewResult.Ready(latestRoute)), events)
        assertSame(latestRoute, coordinator.reusableRoute(latestRoute, RoutePreviewRequest(origin, destination, TravelMode.CAR)))
        assertNull(coordinator.reusableRoute(latestRoute, RoutePreviewRequest(origin, destination, TravelMode.FOOT)))
        coordinator.clear()
        assertNull(coordinator.reusableRoute(latestRoute, RoutePreviewRequest(origin, destination, TravelMode.CAR)))
        scope.cancel()
    }

    @Test
    fun cancelledFailureIsIgnoredButCurrentFailureAndMissingEndpointArePublished() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pending = mutableListOf<Continuation<Route>>()
        val coordinator = RoutePreviewCoordinator(scope, pendingRouter(pending))
        val origin = GeoPoint(50.0, 30.0)
        val destination = GeoPoint(50.02, 30.02)
        val events = mutableListOf<PreviewResult>()

        coordinator.update(origin, destination, TravelMode.CAR, events::add)
        dispatcher.drain()
        coordinator.update(origin, destination, TravelMode.FOOT, events::add)
        dispatcher.drain()
        pending[0].resumeWithException(IllegalStateException("stale error"))
        dispatcher.drain()
        assertEquals(listOf(PreviewResult.Loading, PreviewResult.Loading), events)

        pending[1].resumeWithException(IllegalStateException("current error"))
        dispatcher.drain()
        assertEquals(PreviewResult.Failed("current error"), events.last())
        coordinator.update(null, destination, TravelMode.FOOT, events::add)
        assertEquals(PreviewResult.Cleared, events.last())
        scope.cancel()
    }

    private fun pendingRouter(pending: MutableList<Continuation<Route>>) = object : Router {
        override suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint>, mode: TravelMode): Route = suspendCoroutine { pending += it }
    }

    private fun route(from: GeoPoint, to: GeoPoint) = Route(listOf(from, to), emptyList(), 60.0)

    /** Makes late router completions deterministic without Android's main looper. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
