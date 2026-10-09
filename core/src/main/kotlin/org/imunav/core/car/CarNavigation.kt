package org.imunav.core.car

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Only finite coordinates or a bounded search query may enter the shared route editor. */
sealed interface CarDestination {
    data class Point(val point: GeoPoint) : CarDestination
    data class Query(val text: String) : CarDestination

    companion object {
        /** Parse the geo URI used by navigation hosts, never treating an intent as consent to start. */
        fun parse(value: String?): CarDestination? {
            if (value == null || value.length > 2048) return null
            return runCatching {
                val uri = URI(value)
                if (uri.scheme != "geo") return null
                val body = uri.rawSchemeSpecificPart
                val query = body.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }
                    ?.substringAfter('=')?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }?.trim()
                val coordinates = (query ?: body.substringBefore('?')).substringBefore('(').split(',')
                val lat = coordinates.getOrNull(0)?.trim()?.toDoubleOrNull()
                val lon = coordinates.getOrNull(1)?.trim()?.toDoubleOrNull()
                when {
                    coordinates.size == 2 && lat != null && lon != null ->
                        if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) Point(GeoPoint(lat, lon)) else null

                    !query.isNullOrBlank() && query.length <= 256 && query.none(Char::isISOControl) -> Query(query)

                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** Host demonstration advances a copied route without touching the engine, sensors or trip recorder. */
class CarDemo(private val route: Route, private val startedMs: Long) {
    /** A deterministic simulation makes host testing possible without feeding fake positioning inputs. */
    fun state(elapsedMs: Long): GuidanceState {
        val speed = if (route.durationS > 0) route.length / route.durationS else 10.0
        val s = ((elapsedMs - startedMs).coerceAtLeast(0) / 1000.0 * speed).coerceAtMost(route.length)
        val next = route.steps.indices.firstOrNull { route.stepS(it) > s } ?: route.steps.lastIndex
        val point = route.pointAt(s)
        return GuidanceState(
            active = true, route = route, s = s, position = point.point, bearingDeg = point.bearingDeg.toFloat(),
            nextStep = route.steps.getOrNull(next), nextStepIndex = next,
            distToNextM = if (next >= 0) (route.stepS(next) - s).coerceAtLeast(0.0) else 0.0,
            thenStep = route.steps.getOrNull(next + 1), remainingM = route.length - s,
            remainingS = if (speed > 0) (route.length - s) / speed else 0.0,
            speedKmh = if (s < route.length) (speed * 3.6).toFloat() else 0f,
            arrived = s >= route.length, source = PositionSource.DR, destination = route.geometry.lastOrNull(),
        )
    }
}
