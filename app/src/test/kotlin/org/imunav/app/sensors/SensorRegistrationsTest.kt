package org.imunav.app.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRegistrationsTest {
    @Test
    fun failedRegistrationIsRetriedWithoutRestartingSuccessfulSensors() {
        val attempts = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var available = false
        val registrations = SensorRegistrations<String>(
            { sensor, _ ->
                attempts.add(sensor)
                sensor == "gyro" || available
            },
            removed::add,
            failures::add,
        )
        val wanted = mapOf("gyro" to 40_000, "pressure" to 200_000)
        registrations.reconcile(wanted)
        assertTrue(registrations.contains("gyro"))
        assertFalse(registrations.contains("pressure"))
        registrations.ensure("accelerometer", 40_000)
        assertEquals(listOf("gyro", "pressure", "accelerometer"), attempts)
        assertEquals(listOf("pressure", "accelerometer"), failures)
        available = true
        registrations.reconcile(wanted)
        assertEquals(listOf("gyro", "pressure", "accelerometer", "pressure"), attempts)
        assertEquals(listOf("pressure", "accelerometer"), failures)
        assertTrue(removed.isEmpty())
        assertTrue(registrations.contains("pressure"))
    }

    @Test
    fun rateChangesAndStoppingReleaseExactlyTheRegisteredListeners() {
        val released = mutableListOf<String>()
        val registrations = SensorRegistrations<String>({ _, _ -> true }, released::add, { error("unexpected failure") })
        registrations.reconcile(mapOf("gyro" to 40_000, "pressure" to 200_000))
        registrations.reconcile(mapOf("gyro" to 100_000, "pressure" to 200_000))
        assertEquals(listOf("gyro"), released)
        registrations.reconcile(emptyMap())
        assertFalse(registrations.contains("gyro"))
        assertFalse(registrations.contains("pressure"))
        assertEquals(3, released.size)
        registrations.reconcile(emptyMap())
        assertEquals(3, released.size)
    }
}
