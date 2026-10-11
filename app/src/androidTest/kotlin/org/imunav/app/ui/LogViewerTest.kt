package org.imunav.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.app.R
import org.imunav.app.ui.theme.ImuNavTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production viewer with synthetic lines, including duplicates and constrained space. */
@RunWith(AndroidJUnit4::class)
class LogViewerTest {
    @JvmField
    @Rule
    val compose = createEmptyComposeRule()

    @Test
    fun filtersSearchAndExportsPreserveRawLines() {
        val problem = "12:00:00.001 [1] gps_state=LOST reason=test"
        val normal = "12:00:00.002 [2] route_ready steps=3"
        var copied = emptyList<String>()
        var shared = emptyList<String>()
        withViewer({ LogViewer(listOf(problem, normal), false, {}, {}, { copied = it }, { shared = it }) }) {
            compose.onNode(hasText(label(R.string.trip_log_filter_problems)) and hasClickAction()).performClick()
            compose.onNodeWithContentDescription(label(R.string.trip_log_copy)).performClick()
            compose.onNodeWithContentDescription(label(R.string.trip_log_share)).performClick()
            compose.runOnIdle {
                assertEquals(listOf(problem), copied)
                assertEquals(listOf(problem), shared)
            }
            compose.onNode(hasSetTextAction()).performTextInput("missing_test_event")
            compose.onNodeWithText(label(R.string.trip_log_no_matches)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.trip_log_reset_filters)).performClick()
            compose.onNodeWithContentDescription(label(R.string.trip_log_copy)).performClick()
            compose.runOnIdle { assertEquals(listOf(problem, normal), copied) }
        }
    }

    @Test
    fun detailsExposeElapsedTimeAndCopyOneOriginalEvent() {
        val raw = "12:00:00.001 [12345] gps_state=GOOD details=[test]"
        var copied = emptyList<String>()
        withViewer({ LogViewer(listOf(raw), false, {}, {}, { copied = it }, {}) }) {
            compose.onNodeWithText(label(R.string.trip_log_details)).performClick()
            compose.onNodeWithText(label(R.string.trip_log_elapsed, 12345L)).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.trip_log_copy_event)).performClick()
            compose.runOnIdle { assertEquals(listOf(raw), copied) }
            compose.onNodeWithText(label(R.string.trip_log_collapse)).performClick()
            compose.onNodeWithText(label(R.string.trip_log_details)).assertIsDisplayed()
        }
    }

    @Test
    fun draggingSuspendsFollowingAndLatestRestoresIt() {
        val lines = List(30) { "12:00:00.001 [1] route_step=$it" }
        withViewer({ LogViewer(lines, false, {}, {}, {}, {}) }) {
            compose.onNodeWithTag("log_events").performTouchInput { swipeDown() }
            compose.onNodeWithText(label(R.string.trip_log_latest)).assertIsDisplayed().performClick()
            compose.onNodeWithText(label(R.string.trip_log_follow)).assertIsSelected()
        }
    }

    @Test
    fun wrappedControlsRemainReachableWithLargeFontsAndDuplicateLines() {
        val raw = "12:00:00.001 [1] gps_state=GOOD"
        withViewer({
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                var paused by remember { mutableStateOf(false) }
                Box(Modifier.size(320.dp, 360.dp)) {
                    LogViewer(listOf(raw, raw), paused, { paused = it }, {}, {}, {})
                }
            }
        }) {
            compose.onNodeWithText(label(R.string.trip_log_live)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.trip_log_paused)).assertIsDisplayed().performClick()
            compose.onNodeWithText(label(R.string.trip_log_live)).assertIsDisplayed()
            compose.onAllNodesWithText("gps_state=GOOD")[0].assertExists()
        }
    }

    private fun label(id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    /** Replace only the activity content; keep its real lifecycle and localized resource context. */
    private fun withViewer(content: @Composable () -> Unit, assertions: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { ImuNavTheme { content() } } }
            compose.waitForIdle()
            assertions()
        }
    }
}
