package org.imunav.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingLayoutTest {
    @Test fun panesUseAvailableBoundsAndReserveMostOfTheMap() {
        assertNull(drivingPaneWidth(400.dp, 800.dp))
        assertNull(drivingPaneWidth(599.dp, 320.dp))
        assertNull(drivingPaneWidth(800.dp, 800.dp))
        assertNull(drivingPaneWidth(600.dp, 320.dp))
        assertNull(drivingPaneWidth(799.dp, 320.dp))
        assertEquals(320.dp, drivingPaneWidth(800.dp, 320.dp))
        assertEquals(320.dp, drivingPaneWidth(1000.dp, 400.dp))
    }

    @Test fun enlargedTextFallsBackInsteadOfSqueezingSearchAndBookmarks() {
        assertNull(drivingPaneWidth(1000.dp, 400.dp, fontScale = 1.5f))
        assertEquals(480.dp, drivingPaneWidth(1200.dp, 600.dp, fontScale = 1.5f))
        assertNull(drivingPaneWidth(1280.dp, 800.dp, fontScale = 2f))
        assertEquals(640.dp, drivingPaneWidth(1600.dp, 900.dp, fontScale = 2f))
        assertEquals(320.dp, drivingPaneWidth(800.dp, 320.dp, fontScale = 0.85f))
    }

    @Test fun resizingPreservesReadablePanesAndAMapMajority() {
        for (width in listOf(320, 360, 600, 800, 1024, 1280, 1600)) {
            for (height in listOf(240, 360, 640, 900)) {
                for (fontScale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                    val pane = drivingPaneWidth(width.dp, height.dp, fontScale) ?: continue
                    assertTrue(width > height)
                    assertTrue(pane >= 320.dp * fontScale.coerceAtLeast(1f))
                    assertTrue(pane <= width.dp * 0.4f)
                }
            }
        }
    }
}
