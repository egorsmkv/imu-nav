package org.imunav.app.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.imunav.app.routing.Router
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.RoadPreference
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
    fun preferenceAndPackChangesRejectCachedGeometry() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pending = mutableListOf<Continuation<Route>>()
        val coordinator = RoutePreviewCoordinator(scope, pendingRouter(pending))
        val origin = GeoPoint(0.0, 0.0)
        val destination = GeoPoint(0.01, 0.01)
        val result = route(origin, destination)
        coordinator.update(origin, destination, emptyList(), TravelMode.CAR, {}, RoadPreference.BALANCED, "pack-1")
        dispatcher.drain()
        pending.single().resume(result)
        dispatcher.drain()
        val matching = RoutePreviewRequest(origin, destination, TravelMode.CAR, preference = RoadPreference.BALANCED, packKey = "pack-1")
        assertSame(result, coordinator.reusableRoute(result, matching))
        assertNull(coordinator.reusableRoute(result, matching.copy(preference = RoadPreference.MAIN_ROADS)))
        assertNull(coordinator.reusableRoute(result, matching.copy(packKey = "pack-2")))
        scope.cancel()
    }

    @Test
    fun alternativeCardsAreOnlyPublishedForDistinctRoads() {
        val origin = GeoPoint(0.0, 0.0)
        val destination = GeoPoint(0.01, 0.01)
        val main = route(origin, destination)
        val shortcut = Route(listOf(origin, GeoPoint(0.005, 0.006), destination), emptyList(), 50.0)
        for (other in listOf(main, shortcut)) {
            val dispatcher = QueuedDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val calls = mutableListOf<RoadPreference>()
            val router = object : Router {
                override suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint>, mode: TravelMode, preference: RoadPreference): Route {
                    calls += preference
                    return if (preference == RoadPreference.MAIN_ROADS) main else other
                }
            }
            val coordinator = RoutePreviewCoordinator(scope, router, dispatcher)
            val events = mutableListOf<PreviewResult>()
            coordinator.update(origin, destination, emptyList(), TravelMode.CAR, {
                events += it
            }, alternatives = true, packKey = "pack")
            dispatcher.drain()
            if (other === shortcut) {
                val ready = events.filterIsInstance<PreviewResult.Ready>().last()
                assertEquals(mapOf(RoadPreference.MAIN_ROADS to main, RoadPreference.BALANCED to shortcut), ready.alternatives)
                assertSame(shortcut, coordinator.selectAlternative(RoadPreference.BALANCED, ready.alternatives, "pack"))
                assertSame(
                    shortcut,
                    coordinator.reusableRoute(shortcut, RoutePreviewRequest(origin, destination, TravelMode.CAR, preference = RoadPreference.BALANCED, packKey = "pack")),
                )
                assertNull(coordinator.selectAlternative(RoadPreference.MAIN_ROADS, ready.alternatives, "new-pack"))
            } else {
                assertEquals(1, events.filterIsInstance<PreviewResult.Ready>().size)
            }
            assertEquals(listOf(RoadPreference.MAIN_ROADS, RoadPreference.BALANCED), calls)
            scope.cancel()
        }
    }

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

        coordinator.update(origin, oldDestination, emptyList(), TravelMode.CAR, events::add)
        dispatcher.drain()
        coordinator.update(origin, destination, emptyList(), TravelMode.CAR, events::add)
        dispatcher.drain()
        pending[0].resume(oldRoute)
        dispatcher.drain()
        assertEquals(listOf(PreviewResult.Loading, PreviewResult.Loading), events)

        pending[1].resume(latestRoute)
        dispatcher.drain()
        assertEquals(listOf(PreviewResult.Loading, PreviewResult.Loading, PreviewResult.Ready(latestRoute)), events)
        assertSame(latestRoute, coordinator.reusableRoute(latestRoute, RoutePreviewRequest(origin, destination, TravelMode.CAR)))
        assertNull(coordinator.reusableRoute(latestRoute, RoutePreviewRequest(origin, destination, TravelMode.FOOT)))
        // A route planned without the stop the user just added is not the route they asked for.
        assertNull(coordinator.reusableRoute(latestRoute, RoutePreviewRequest(origin, destination, TravelMode.CAR, listOf(oldDestination))))
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

        coordinator.update(origin, destination, emptyList(), TravelMode.CAR, events::add)
        dispatcher.drain()
        coordinator.update(origin, destination, emptyList(), TravelMode.FOOT, events::add)
        dispatcher.drain()
        pending[0].resumeWithException(IllegalStateException("stale error"))
        dispatcher.drain()
        assertEquals(listOf(PreviewResult.Loading, PreviewResult.Loading), events)

        pending[1].resumeWithException(IllegalStateException("current error"))
        dispatcher.drain()
        assertEquals(PreviewResult.Failed("current error"), events.last())
        coordinator.update(null, destination, emptyList(), TravelMode.FOOT, events::add)
        assertEquals(PreviewResult.Cleared, events.last())
        scope.cancel()
    }

    private fun pendingRouter(pending: MutableList<Continuation<Route>>) = object : Router {
        override suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint>, mode: TravelMode, preference: RoadPreference): Route = suspendCoroutine { pending += it }
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
