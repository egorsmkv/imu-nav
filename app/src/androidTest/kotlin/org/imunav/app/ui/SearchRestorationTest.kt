package org.imunav.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercise production search, including its parent overlay, across actual Activity recreation. */
@RunWith(AndroidJUnit4::class)
class SearchRestorationTest {
    // Public field avoids the pinned lint frontend's crash on @get:Rule use-site targets.
    @JvmField
    @Rule
    val compose = createEmptyComposeRule()

    @Test
    fun openSearchAndQuerySurviveRecreationAndRotationResize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val setup = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
        val originallyCompleted = setup.getBoolean("completed", false)
        setup.edit(commit = true) { putBoolean("completed", true) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var fromLabel = ""
                scenario.onActivity { fromLabel = it.getString(R.string.route_from) }
                compose.onNodeWithText(fromLabel).performClick()
                // One letter avoids an online geocoder request; this test owns no private address.
                compose.onNode(hasSetTextAction()).performTextInput("К")
                scenario.recreate()
                compose.onNode(hasSetTextAction()).assertTextEquals("К")
                val orientation = AtomicInteger()
                scenario.onActivity {
                    orientation.set(it.resources.configuration.orientation)
                    it.requestedOrientation = if (orientation.get() == Configuration.ORIENTATION_LANDSCAPE) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                }
                compose.waitUntil(10_000) {
                    var rotated = false
                    scenario.onActivity { rotated = it.resources.configuration.orientation != orientation.get() }
                    rotated
                }
                compose.onNode(hasSetTextAction()).assertTextEquals("К")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally {
            setup.edit(commit = true) { putBoolean("completed", originallyCompleted) }
        }
    }
}
