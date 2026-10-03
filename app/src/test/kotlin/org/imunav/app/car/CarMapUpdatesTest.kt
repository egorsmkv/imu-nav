package org.imunav.app.car

import org.imunav.app.UiState
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMapUpdatesTest {
    @Test
    fun hiddenUpdatesAreDeferredAndLatestStateIsAppliedOnceOnResume() {
        val updates = CarMapUpdates()
        val before = carMapContent(UiState(currentPosition = GeoPoint(50.0, 30.0)), true, 30)
        val after = carMapContent(UiState(currentPosition = GeoPoint(51.0, 30.0)), true, 15)
        assertTrue(requireNotNull(updates.update(before, true)).position)
        assertNull(updates.update(after, false))
        val resumed = requireNotNull(updates.update(after, true))
        assertTrue(resumed.position)
        assertTrue(resumed.camera)
        assertTrue(resumed.maximumFps)
        assertFalse(resumed.destination)
        val repeated = requireNotNull(updates.update(after, true))
        assertFalse(repeated.position)
        assertFalse(repeated.camera)
        assertFalse(repeated.maximumFps)
    }

    @Test
    fun styleReplacementClearsStaleSourcesAndRecenterReappliesCamera() {
        val updates = CarMapUpdates()
        val state = carMapContent(UiState(currentPosition = GeoPoint(50.0, 30.0)), true, 30)
        updates.update(state, true)
        updates.invalidateCamera()
        assertTrue(requireNotNull(updates.update(state, true)).camera)
        updates.reset()
        val reset = requireNotNull(updates.update(state, true))
        assertTrue(reset.destination) // Includes null: old style destinations must be cleared.
        assertTrue(reset.position)
        assertTrue(reset.maximumFps)
    }
}
