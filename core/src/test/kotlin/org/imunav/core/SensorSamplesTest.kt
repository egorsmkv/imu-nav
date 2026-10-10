package org.imunav.core

import org.imunav.core.imu.GravityFilter
import org.imunav.core.imu.TimedSensorVector
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SensorSamplesTest {
    @Test
    fun gravityResponseUsesMeasuredTimeAtEveryPowerProfileRate() {
        for (hz in listOf(10, 25, 50)) {
            val filter = GravityFilter()
            filter.linearAcceleration(floatArrayOf(0f, 0f, 0f), 0)
            var result: FloatArray? = null
            for (sample in 1..hz) result = filter.linearAcceleration(floatArrayOf(10f, 0f, 0f), sample * 1_000_000_000L / hz)
            assertEquals(10.0 * exp(-1.0), assertNotNull(result)[0].toDouble(), 0.00002)
        }
    }

    @Test
    fun gravityRejectsDuplicateReorderedAndInvalidEventsWithoutAdvancingTime() {
        val filter = GravityFilter()
        filter.linearAcceleration(floatArrayOf(0f, 0f, 0f), 0)
        assertNull(filter.linearAcceleration(floatArrayOf(100f, 0f, 0f), 0))
        assertNull(filter.linearAcceleration(floatArrayOf(Float.NaN, 0f, 0f), 2_000_000_000L))
        val result = filter.linearAcceleration(floatArrayOf(10f, 0f, 0f), 100_000_000L)
        assertEquals(10.0 * exp(-0.1), assertNotNull(result)[0].toDouble(), 0.000002)
        assertNull(filter.linearAcceleration(floatArrayOf(100f, 0f, 0f), 50_000_000L))
    }

    @Test
    fun restartAndLongGapReanchorGravityWithoutInventingAnAccelerationSpike() {
        val filter = GravityFilter()
        filter.linearAcceleration(floatArrayOf(0f, 0f, 10f), 0)
        assertEquals(0f, assertNotNull(filter.linearAcceleration(floatArrayOf(10f, 0f, 0f), 2_000_000_000L))[0])
        filter.reset()
        assertEquals(0f, assertNotNull(filter.linearAcceleration(floatArrayOf(0f, 10f, 0f), 1))[1])
    }

    @Test
    fun vectorsKeepMeasurementAgeAndCannotBeReplacedByOldOrMutatedInput() {
        val vector = TimedSensorVector()
        val input = floatArrayOf(1f, 2f, 3f)
        vector.update(input, 1_000_000_000L)
        input[0] = 100f
        vector.update(floatArrayOf(5f, 5f, 5f), 999_000_000L)
        vector.update(floatArrayOf(Float.NaN, 5f, 5f), 2_000_000_000L)
        assertNull(vector.at(999_999_999L))
        assertEquals(1f, assertNotNull(vector.at(1_250_000_000L))[0])
        assertNull(vector.at(1_250_000_001L))
        vector.reset()
        assertNull(vector.at(1_000_000_000L))
    }
}
