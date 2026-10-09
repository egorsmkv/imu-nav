package org.imunav.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceChangesTest {
    @Test
    fun estimatorAndOtherSettingsKeepTheirOrderBeforeTravelMode() {
        val incoming = linkedMapOf(
            "setting:travel_mode" to "\"FOOT\"",
            "setting:voice" to "false",
            "setting:navigation_estimator" to "\"NATIVE\"",
            "setting:language" to "\"uk\"",
        )
        assertEquals(
            listOf(
                PreferenceChange("setting:voice", "false"),
                PreferenceChange("setting:navigation_estimator", "\"NATIVE\""),
                PreferenceChange("setting:language", "\"uk\""),
                PreferenceChange("setting:travel_mode", "\"FOOT\""),
            ),
            preferenceChanges(emptyMap(), incoming),
        )
    }

    @Test
    fun unchangedSettingsBookmarksAndMissingIncomingKeysDoNotTriggerSetters() {
        val current = mapOf("setting:voice" to "true", "setting:language" to "\"en\"")
        val incoming = mapOf("setting:voice" to "true", "bookmark:one" to "{}", "setting" to "false")
        assertTrue(preferenceChanges(current, incoming).isEmpty())
    }

    @Test
    fun planningDoesNotMutateInputsAndValuesAreDetachedFromLaterEdits() {
        val current = linkedMapOf("setting:voice" to "true")
        val incoming = linkedMapOf("setting:voice" to "false")
        val changes = preferenceChanges(current, incoming)
        assertEquals(mapOf("setting:voice" to "true"), current)
        assertEquals(mapOf("setting:voice" to "false"), incoming)
        incoming["setting:voice"] = "true"
        current.clear()
        assertEquals(listOf(PreferenceChange("setting:voice", "false")), changes)
    }

    @Test
    fun selectionPreservesEncodedValuesWithoutParsingOrDroppingUnknownSettings() {
        // Validation/JSON errors stay at the existing boundary; planning adds no new policy.
        val incoming = mapOf("setting:future" to "not-json", "setting:voice" to " false ")
        assertEquals(
            listOf(PreferenceChange("setting:future", "not-json"), PreferenceChange("setting:voice", " false ")),
            preferenceChanges(mapOf("setting:voice" to "false"), incoming),
        )
        assertTrue(preferenceChanges(emptyMap(), emptyMap()).isEmpty())
    }
}
