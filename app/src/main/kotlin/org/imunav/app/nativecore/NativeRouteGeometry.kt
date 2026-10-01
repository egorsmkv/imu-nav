package org.imunav.app.nativecore

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Projection
import org.imunav.core.route.Route
import java.io.Closeable

/**
 * Immutable route geometry prepared by Rust away from the Android main thread.
 *
 * A filter retains the underlying geometry when [NativeRouteFilter.installRoute] is called, so this
 * owner can be closed immediately after attachment.
 */
class NativeRouteGeometry private constructor(internal var handle: Long) : Closeable {
    fun project(latitudeDeg: Double, longitudeDeg: Double, aroundM: Double, behindM: Double, aheadM: Double, globalIfFartherM: Double): Projection {
        val values = nativeProject(requireHandle(), latitudeDeg, longitudeDeg, aroundM, behindM, aheadM, globalIfFartherM)
            ?: error("native route projection failed")
        check(values.size == PROJECTION_SIZE) { "native route projection returned ${values.size} values" }
        return Projection(values[0], values[1], values[2].toInt(), GeoPoint(values[3], values[4]))
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        check(nativeDestroy(current) == RESULT_OK) { "native route geometry destroy failed" }
    }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "native route geometry is closed" } }

    companion object {
        private const val RESULT_OK = 0
        private const val PROJECTION_SIZE = 5

        init {
            System.loadLibrary("imu_nav_jni")
        }

        /** Encode and index a route; call this on a worker dispatcher for non-trivial routes. */
        fun create(route: Route): NativeRouteGeometry {
            val coordinates = DoubleArray(route.geometry.size * COORDINATES_PER_POINT)
            route.geometry.forEachIndexed { index, point ->
                coordinates[index * COORDINATES_PER_POINT] = point.lat
                coordinates[index * COORDINATES_PER_POINT + 1] = point.lon
            }
            val handle = nativeCreate(coordinates)
            check(handle != 0L) { "could not create native route geometry" }
            return NativeRouteGeometry(handle)
        }

        @JvmStatic
        private external fun nativeCreate(coordinates: DoubleArray): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long): Int

        @JvmStatic
        private external fun nativeProject(
            handle: Long,
            latitudeDeg: Double,
            longitudeDeg: Double,
            aroundM: Double,
            behindM: Double,
            aheadM: Double,
            globalIfFartherM: Double,
        ): DoubleArray?

        private const val COORDINATES_PER_POINT = 2
    }
}
