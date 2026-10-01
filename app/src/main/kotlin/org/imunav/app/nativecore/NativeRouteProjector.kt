package org.imunav.app.nativecore

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Projection
import org.imunav.core.route.Route
import org.imunav.core.route.RouteProjector
import java.io.Closeable

/** Main-thread owner that makes prepared Rust route geometry authoritative for observations. */
class NativeRouteProjector :
    RouteProjector,
    Closeable {
    private var installedRoute: Route? = null
    private var geometry: NativeRouteGeometry? = null

    /** Takes ownership of [geometry] and releases the previously installed route. */
    fun install(route: Route, geometry: NativeRouteGeometry) {
        this.geometry?.close()
        installedRoute = route
        this.geometry = geometry
    }

    override fun project(route: Route, point: GeoPoint, aroundS: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection {
        val nativeGeometry = geometry
        return if (route === installedRoute && nativeGeometry != null) {
            nativeGeometry.project(point.lat, point.lon, aroundS, behindM, aheadM, globalIfFartherM)
        } else {
            // Trip restoration starts the engine before its route can be prepared on a worker.
            route.project(point, aroundS, behindM, aheadM, globalIfFartherM)
        }
    }

    override fun close() {
        geometry?.close()
        geometry = null
        installedRoute = null
    }
}
