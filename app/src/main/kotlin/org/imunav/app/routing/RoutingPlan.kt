package org.imunav.app.routing

import org.imunav.core.route.TravelMode

/** Routing policy only; the router owns requests, logging, errors and cancellation. */
internal enum class RoutingPlan { OFFLINE_ONLY, OFFLINE_WITH_FALLBACK, ONLINE_ONLY, WALKING_UNAVAILABLE, OFFLINE_UNAVAILABLE }

/** Walking never falls back to the driving service, even when online routing is enabled. */
internal fun routingPlan(mode: TravelMode, covered: Boolean, supported: Boolean, onlineAllowed: Boolean): RoutingPlan = when {
    mode == TravelMode.FOOT && (!covered || !supported) -> RoutingPlan.WALKING_UNAVAILABLE
    covered && onlineAllowed && mode == TravelMode.CAR -> RoutingPlan.OFFLINE_WITH_FALLBACK
    covered -> RoutingPlan.OFFLINE_ONLY
    onlineAllowed && mode == TravelMode.CAR -> RoutingPlan.ONLINE_ONLY
    else -> RoutingPlan.OFFLINE_UNAVAILABLE
}
