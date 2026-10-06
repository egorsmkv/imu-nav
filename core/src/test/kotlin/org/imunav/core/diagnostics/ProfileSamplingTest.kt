package org.imunav.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSamplingTest {
    @Test fun detailedScansAreSpacedWhileCheapSamplesContinue() {
        val schedule = ProfileSampling()
        val start = 800_000L
        assertEquals(ProfileSampling.Kind.DETAILED, schedule.next(start))
        assertNull(schedule.next(start + 4_999))
        assertEquals(ProfileSampling.Kind.CHEAP, schedule.next(start + 5_000))
        assertEquals(ProfileSampling.Kind.CHEAP, schedule.next(start + 10_000))
        assertNull(schedule.next(start + 14_999))
        assertEquals(ProfileSampling.Kind.DETAILED, schedule.next(start + 15_000))
        assertEquals(ProfileSampling.Kind.DETAILED, schedule.next(start + 45_000))
    }

    @Test fun shortCaptureStillEndsWithDetailedMemory() {
        val schedule = ProfileSampling()
        assertEquals(ProfileSampling.Kind.DETAILED, schedule.next(0))
        assertEquals(ProfileSampling.Kind.DETAILED, schedule.next(1, finishing = true))
    }
}
