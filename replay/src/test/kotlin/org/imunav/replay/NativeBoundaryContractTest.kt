package org.imunav.replay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses the actual JVM ABI, bypassing Kotlin preconditions to verify native validation and ownership. */
class NativeBoundaryContractTest {
    /** Reflection keeps malformed-wire testing out of the app's public API. */
    private fun call(owner: String, method: String, vararg args: Any): Any? {
        val type = Class.forName("org.imunav.app.nativecore.Native$owner")
        val native = type.declaredMethods.single { it.name == "native$method" }
        native.isAccessible = true
        return native.invoke(null, *args)
    }

    private fun route(): Long = call("RouteGeometry", "Create", doubleArrayOf(50.0, 30.0, 50.01, 30.0)) as Long

    @Test
    fun filterWireOrderGatesAndSharedRouteOwnership() {
        val filter = call("RouteFilter", "Create", 10.0, 5.0, 3.0, 2.0, 4.0) as Long
        val geometry = route()
        try {
            assertContentEquals(doubleArrayOf(10.0, 5.0, 9.0, 0.0, 4.0, 4.0, 10.0), call("RouteFilter", "GetState", filter) as DoubleArray)
            assertNull(call("RouteFilter", "Project", filter, 50.005, 30.0, 0.0, 0.0, 2000.0, 100.0))
            assertEquals(0, call("RouteFilter", "InstallRoute", filter, geometry))
            val projected = call("RouteGeometry", "Project", geometry, 50.005, 30.0, 0.0, 0.0, 2000.0, 100.0) as DoubleArray
            assertEquals(5, projected.size)
            assertEquals(50.005, projected[3], 0.000001)
            assertEquals(30.0, projected[4], 0.000001)
            assertEquals(0, call("RouteGeometry", "Destroy", geometry))
            assertContentEquals(projected, call("RouteFilter", "Project", filter, 50.005, 30.0, 0.0, 0.0, 2000.0, 100.0) as DoubleArray)
            assertEquals(0, call("RouteFilter", "Predict", filter, 1.0, 0.5, 0.1))
            assertEquals(0, call("RouteFilter", "UpdatePosition", filter, 15.0, 3.0, 9.0))
            assertEquals(0, call("RouteFilter", "UpdateSpeed", filter, 5.0, 2.0, 9.0))
            assertEquals(1, call("RouteFilter", "UpdatePosition", filter, 1e6, 1.0, 1.0))
            assertEquals(1, call("RouteFilter", "UpdateSpeed", filter, 1e6, 1.0, 1.0))
            assertEquals(0, call("RouteFilter", "AnchorPosition", filter, 100.0, 2.0))
            assertEquals(0, call("RouteFilter", "ResetSystematicDrift", filter))
            val anchored = call("RouteFilter", "GetState", filter) as DoubleArray
            assertEquals(100.0, anchored[0])
            assertEquals(0.0, anchored[5])
            assertEquals(-2, call("RouteFilter", "Predict", filter, -1.0, 1.0, 0.0))
            assertEquals(-2, call("RouteFilter", "UpdatePosition", filter, Double.NaN, 1.0, 1.0))
            assertEquals(-2, call("RouteFilter", "UpdateSpeed", filter, 1.0, -1.0, 1.0))
            assertEquals(-2, call("RouteFilter", "AnchorPosition", filter, 1.0, -1.0))
            assertNull(call("RouteFilter", "Project", filter, Double.NaN, 30.0, 0.0, 0.0, 10.0, 100.0))
            assertEquals(-1, call("RouteFilter", "InstallRoute", filter, geometry))
        } finally {
            call("RouteFilter", "Destroy", filter)
            call("RouteGeometry", "Destroy", geometry)
        }
        for (invalid in listOf(0L, -1L, filter)) {
            assertEquals(-1, call("RouteFilter", "Destroy", invalid))
            assertEquals(-1, call("RouteFilter", "Predict", invalid, 1.0, 1.0, 0.0))
            assertEquals(-1, call("RouteFilter", "UpdatePosition", invalid, 1.0, 1.0, 1.0))
            assertEquals(-1, call("RouteFilter", "UpdateSpeed", invalid, 1.0, 1.0, 1.0))
            assertEquals(-1, call("RouteFilter", "AnchorPosition", invalid, 1.0, 1.0))
            assertEquals(-1, call("RouteFilter", "ResetSystematicDrift", invalid))
            assertNull(call("RouteFilter", "GetState", invalid))
            assertNull(call("RouteFilter", "Project", invalid, 50.0, 30.0, 0.0, 0.0, 10.0, 100.0))
        }
        assertEquals(0L, call("RouteFilter", "Create", Double.NaN, 0.0, 1.0, 1.0, 0.0))
        for (coordinates in listOf(doubleArrayOf(), doubleArrayOf(50.0, 30.0, 51.0), doubleArrayOf(Double.NaN, 30.0, 51.0, 30.0))) {
            assertEquals(0L, call("RouteGeometry", "Create", coordinates))
        }
        assertNull(call("RouteGeometry", "Project", geometry, 50.0, 30.0, 0.0, 0.0, 10.0, 100.0))
    }

    @Test
    fun trustWireVerdictsResetAndMalformedArrays() {
        val handle = call("TrustEvaluator", "Create") as Long
        val doubles = doubleArrayOf(50.0, 30.0, 100.0, 0.0, 0.0, 5.0, 3.0, 50.0, 30.0, 20.0, 35.0, 5.0, 0.0, Double.NaN)
        val longs = longArrayOf(1000, 1000, 0, 1, 1, 1000, 12, 10, 2, 1000, 1000)
        try {
            assertContentEquals(intArrayOf(0), call("TrustEvaluator", "Evaluate", handle, doubles, longs) as IntArray)
            val duplicate = call("TrustEvaluator", "Evaluate", handle, doubles, longs) as IntArray
            assertEquals(2, duplicate[0])
            assertTrue(7 in duplicate)
            assertEquals(0, call("TrustEvaluator", "Reset", handle))
            assertContentEquals(intArrayOf(0), call("TrustEvaluator", "Evaluate", handle, doubles, longs) as IntArray)
            call("TrustEvaluator", "Reset", handle)
            longs[2] = 1
            val mock = call("TrustEvaluator", "Evaluate", handle, doubles, longs) as IntArray
            assertEquals(2, mock[0])
            assertTrue(1 in mock)
            longs[2] = 0
            longs[3] = 0
            assertTrue(2 in (call("TrustEvaluator", "Evaluate", handle, doubles, longs) as IntArray))
            assertNull(call("TrustEvaluator", "Evaluate", handle, DoubleArray(13), longs))
            assertNull(call("TrustEvaluator", "Evaluate", handle, doubles, LongArray(10)))
            longs[6] = -1
            assertNull(call("TrustEvaluator", "Evaluate", handle, doubles, longs))
            longs[6] = 12
            assertEquals(3, call("TrustEvaluator", "UpdateAgc", handle, -20.0, 1000L))
            assertEquals(1, call("TrustEvaluator", "UpdateAgc", handle, Double.NaN, 2000L))
            assertEquals(0, call("TrustEvaluator", "Reset", handle))
            assertEquals(0, call("TrustEvaluator", "UpdateAgc", handle, 0.0, 3000L))
        } finally {
            assertEquals(0, call("TrustEvaluator", "Destroy", handle))
        }
        assertEquals(-1, call("TrustEvaluator", "Destroy", handle))
        assertEquals(-1, call("TrustEvaluator", "Reset", handle))
        assertEquals(-1, call("TrustEvaluator", "UpdateAgc", handle, 0.0, 4000L))
        assertNull(call("TrustEvaluator", "Evaluate", handle, doubles, longs))
    }

    @Test
    fun networkWireSamplesRegressionAndStaleHandles() {
        val handle = call("NetworkTracker", "Create") as Long
        try {
            assertEquals(0, call("NetworkTracker", "LastTwoConsistent", handle))
            assertNull(call("NetworkTracker", "Estimate", handle, 1000L, 0))
            assertEquals(0, call("NetworkTracker", "Gate", handle, 1000L, 0.0, 10.0))
            assertEquals(2, call("NetworkTracker", "Gate", handle, 2000L, 100000.0, 10.0))
            for (index in 0..12) {
                assertEquals(0, call("NetworkTracker", "Record", handle, 1000L + index * 10000L, doubleArrayOf(index * 100.0, 10.0, 0.0, 50.0 + index * 0.001, 30.0)))
            }
            assertEquals(1, call("NetworkTracker", "LastTwoConsistent", handle))
            for (strict in 0..1) {
                val estimate = call("NetworkTracker", "Estimate", handle, 121000L, strict) as DoubleArray
                assertEquals(4, estimate.size)
                assertEquals(10.0, estimate[0], 0.01)
                assertTrue(estimate[1] > 0.0)
                assertTrue(estimate[2] >= 3.0)
                assertTrue(estimate[3] > 0.0)
            }
            val samples = call("NetworkTracker", "Samples", handle, 1) as DoubleArray
            assertEquals(52, samples.size)
            assertContentEquals(doubleArrayOf(1000.0, 0.0, 10.0, 0.0), samples.copyOfRange(0, 4))
            assertEquals(-2, call("NetworkTracker", "Record", handle, 1000L, DoubleArray(4)))
            assertEquals(0, call("NetworkTracker", "PruneHistory", handle, 1_000_000L))
            assertContentEquals(doubleArrayOf(), call("NetworkTracker", "Samples", handle, 1) as DoubleArray)
            assertEquals(0, call("NetworkTracker", "ClearSamples", handle))
            assertEquals(0, call("NetworkTracker", "Reset", handle))
        } finally {
            assertEquals(0, call("NetworkTracker", "Destroy", handle))
        }
        for (invalid in listOf(0L, -1L, handle)) {
            for (method in listOf("Destroy", "Reset", "ClearSamples", "LastTwoConsistent")) assertEquals(-1, call("NetworkTracker", method, invalid))
            assertEquals(-1, call("NetworkTracker", "Gate", invalid, 1000L, 0.0, 10.0))
            assertEquals(-1, call("NetworkTracker", "Record", invalid, 1000L, DoubleArray(5)))
            assertEquals(-1, call("NetworkTracker", "PruneHistory", invalid, 1000L))
            assertNull(call("NetworkTracker", "Estimate", invalid, 1000L, 0))
            assertNull(call("NetworkTracker", "Samples", invalid, 0))
        }
    }

    @Test
    fun estimatorRejectsBadWireAndRetainsReplacementGeometry() {
        val geometry = route()
        val initial = doubleArrayOf(0.0, 5.0, 3.0, 2.0, 0.0)
        val handle = call("NavigationEstimator", "Create", geometry, initial, 0, 1000L) as Long
        try {
            assertTrue(handle > 0)
            assertEquals(0L, call("NavigationEstimator", "Create", geometry, DoubleArray(4), 0, 1000L))
            assertEquals(0L, call("NavigationEstimator", "Create", geometry, initial, 2, 1000L))
            assertEquals(0L, call("NavigationEstimator", "Create", -1L, initial, 0, 1000L))
            assertEquals(-3, call("NavigationEstimator", "SetNetworkSpeedEnabled", handle, 2))
            assertEquals(0, call("NavigationEstimator", "SetNetworkSpeedEnabled", handle, 1))
            assertEquals(1, call("NavigationEstimator", "OnVehicleSpeed", handle, Double.NaN, 1000L))
            assertEquals(-2, call("NavigationEstimator", "ReplaceRoute", handle, geometry, Double.NaN, 3.0))
            assertEquals(0, call("NavigationEstimator", "ReplaceRoute", handle, geometry, 100.0, 3.0))
            assertEquals(0, call("RouteGeometry", "Destroy", geometry))
            assertEquals(-1, call("NavigationEstimator", "ReplaceRoute", handle, geometry, 100.0, 3.0))
            assertNull(call("NavigationEstimator", "Tick", handle, 2000L, DoubleArray(6), LongArray(3)))
            for ((doubles, longs) in listOf(5 to 3, 7 to 6, 10 to 8, 11 to 11)) {
                val state = call("NavigationEstimator", "Tick", handle, 2000L, DoubleArray(doubles), LongArray(longs)) as DoubleArray
                assertEquals(9, state.size)
                assertTrue(state[0] >= 100.0)
                assertEquals(0.0, state[7])
                assertEquals(0.0, state[8])
            }
            assertNull(call("NavigationEstimator", "Tick", handle, 2000L, DoubleArray(5), longArrayOf(1, 2000, 2)))
            assertNull(call("NavigationEstimator", "Tick", handle, 2000L, DoubleArray(7), longArrayOf(0, 0, 0, 1, 2000, 2)))
        } finally {
            assertEquals(0, call("NavigationEstimator", "Destroy", handle))
            call("RouteGeometry", "Destroy", geometry)
        }
        for (invalid in listOf(0L, -1L, handle)) {
            assertEquals(-1, call("NavigationEstimator", "Destroy", invalid))
            assertEquals(-1, call("NavigationEstimator", "SetNetworkSpeedEnabled", invalid, 1))
            assertEquals(-1, call("NavigationEstimator", "OnVehicleSpeed", invalid, 10.0, 2000L))
            assertNull(call("NavigationEstimator", "Tick", invalid, 2000L, DoubleArray(5), LongArray(3)))
        }
    }

    @Test
    fun speedFusionMissingAndInvalidValuesDoNotProduceNan() {
        assertEquals(0.0, call("SpeedFusion", "Fuse", Double.NaN, 0L, Double.NaN, Double.NaN, Double.NaN, 0, 0.0))
        assertEquals(10.0, call("SpeedFusion", "Fuse", 10.0, 0L, Double.NaN, Double.NaN, 1.0, 0, 0.0) as Double, 1e-12)
        val fused = call("SpeedFusion", "Fuse", 10.0, 1000L, 12.0, 11.0, 1.0, 5, 30.0) as Double
        assertTrue(fused in 10.0..12.0)
        assertTrue((call("SpeedFusion", "Fuse", Double.POSITIVE_INFINITY, -1L, -1.0, 1.0, -1.0, -1, 0.0) as Double).isFinite())
    }
}
