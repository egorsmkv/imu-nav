package org.imunav.core

import org.imunav.core.imu.ImuSample
import org.imunav.core.imu.TurnDetector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Device handling and missing samples must not manufacture completed vehicle turns. */
class TurnDetectorTest {
    @Test
    fun gradualYawOnsetAndRecordedBiasStillProduceACompleteTurn() {
        val detector = TurnDetector()
        for (time in 20L..7000L step 20) {
            val yaw = when (time) {
                in 2000..2500 -> (time - 2000) / 20.0
                in 2501..5500 -> 25.0
                in 5501..6000 -> (6000 - time) / 20.0
                else -> 0.0
            }
            detector.add(sample(time, yaw + 1.5), yawBiasDegS = 1.5)
        }
        assertEquals(87.5, assertNotNull(detector.evidence(7000)).angleDeg, 1.5)
    }

    private fun sample(time: Long, yaw: Double, extraGyro: Double = 0.0) =
        ImuSample(time, null, yaw.toFloat(), floatArrayOf(1f, 0f, 0f), floatArrayOf(((abs(yaw) + extraGyro) * PI / 180.0).toFloat(), 0f, 0f))

    private fun drive(yawAt: (Long) -> Double, extraGyro: Double = 0.0, gap: LongRange = LongRange.EMPTY): TurnDetector = TurnDetector().also { detector ->
        for (time in 20L..7000L step 20) {
            if (time !in gap) detector.add(sample(time, yawAt(time), extraGyro))
        }
    }

    @Test
    fun detectsSignedCompletedTurnsAndExpiresWithoutFreshSamples() {
        for (direction in listOf(-1.0, 1.0)) {
            val detector = drive({ if (it in 2020..6000) direction * 22.5 else 0.0 })
            val evidence = assertNotNull(detector.evidence(7000))
            assertEquals(direction * 90.0, evidence.angleDeg, 0.5)
            assertNull(detector.evidence(6999))
            assertNull(detector.evidence(7201))
            for (time in 7020L..8020L step 20) detector.add(sample(time, 0.0))
            assertNull(detector.evidence(8020))
        }
    }

    @Test
    fun rejectsShortSwingsReversalsTiltAndGaps() {
        assertNull(drive({ if (it in 4000..4500) 100.0 else 0.0 }).evidence(7000))
        assertNull(
            drive({
                when (it) {
                    in 2020..4500 -> 30.0
                    in 4501..6000 -> -20.0
                    else -> 0.0
                }
            }).evidence(7000),
        )
        assertNull(drive({ if (it in 2020..6000) 22.5 else 0.0 }, extraGyro = 40.0).evidence(7000))
        assertNull(drive({ if (it in 2020..6000) 22.5 else 0.0 }, gap = 3000L..3500L).evidence(7000))
    }

    @Test
    fun incompleteOrMissingGyroDoesNotEmitAndResetDiscardsOldTurns() {
        assertNull(drive({ if (it > 2000) 12.0 else 0.0 }).evidence(7000))
        val detector = drive({ if (it in 2020..6000) 22.5 else 0.0 })
        assertNotNull(detector.evidence(7000))
        detector.reset()
        assertNull(detector.evidence(7000))
        for (time in 7020L..14_000L step 20) detector.add(ImuSample(time, null, 22.5f, floatArrayOf(1f, 0f, 0f), null))
        assertNull(detector.evidence(14_000))
    }
}
