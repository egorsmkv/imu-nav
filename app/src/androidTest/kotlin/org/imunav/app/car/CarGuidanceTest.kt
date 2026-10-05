package org.imunav.app.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.nav.GuidanceState
import org.imunav.core.route.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarGuidanceTest {
    @Test fun turnsReroutingAndArrivalProduceValidHostModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = TestCarContext.createCarContext(instrumentation.targetContext)
            val adapter = CarGuidance(context)
            val state = GuidanceState(active = true, nextStep = Step("turn", "right"), distToNextM = 150.0, remainingM = 1000.0, remainingS = 90.0)
            val routing = adapter.routing(state)
            assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, routing.currentStep?.maneuver?.type)
            val template = NavigationTemplate.Builder().setNavigationInfo(routing)
                .setDestinationTravelEstimate(adapter.estimate(state.remainingM, state.remainingS, "GPS"))
                .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("Stop").setOnClickListener {}.build()).build()).build()
            assertEquals(routing, template.navigationInfo)
            assertTrue(adapter.routing(state.copy(rerouting = true)).isLoading)
            assertTrue(adapter.trip(state.copy(rerouting = true), "Kyiv", "DR").isLoading)
            assertEquals(Maneuver.TYPE_DESTINATION, adapter.routing(state.copy(arrived = true)).currentStep?.maneuver?.type)
            assertEquals(1, adapter.trip(state, "Kyiv", "DR").destinations.size)
            assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, adapter.routing(state.copy(nextStep = Step("roundabout"))).currentStep?.maneuver?.type)
            assertEquals(3, adapter.routing(state.copy(nextStep = Step("roundabout", roundaboutExit = 3))).currentStep?.maneuver?.roundaboutExitNumber)
            assertEquals(Maneuver.TYPE_UNKNOWN, adapter.routing(state.copy(nextStep = Step("fork"))).currentStep?.maneuver?.type)
        }
    }
}
