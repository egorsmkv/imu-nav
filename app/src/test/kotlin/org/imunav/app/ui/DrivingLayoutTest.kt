package org.imunav.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingLayoutTest {
    @Test fun panesUseAvailableBoundsAndReserveMostOfTheMap() {
        assertNull(drivingPaneWidth(400.dp, 800.dp))
        assertNull(drivingPaneWidth(599.dp, 320.dp))
        assertNull(drivingPaneWidth(800.dp, 800.dp))
        assertEquals(240.dp, drivingPaneWidth(600.dp, 320.dp))
        assertEquals(320.dp, drivingPaneWidth(1000.dp, 400.dp))
    }
}
