package org.imunav.core.route

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.EnglishPhrases
import org.imunav.core.nav.RussianPhrases
import org.imunav.core.nav.UkrainianPhrases
import org.imunav.core.record.RouteCodec
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Route vocabulary must round-trip old wire values without changing guidance or recovery. */
class ManeuverTest {
    @Test
    fun vocabularyIsStrictAndWireValuesAreStable() {
        assertEquals(
            listOf(
                "depart", "arrive", "turn", "new name", "merge", "on ramp", "off ramp", "fork", "end of road", "continue", "roundabout", "rotary",
                "roundabout turn", "exit roundabout", "exit rotary", "notification", "use lane",
            ),
            ManeuverType.entries.map { it.wireValue },
        )
        assertEquals(listOf("straight", "uturn", "left", "right", "slight left", "slight right", "sharp left", "sharp right"), ManeuverModifier.entries.map { it.wireValue })
        ManeuverType.entries.forEach { assertEquals(it, ManeuverType.parse(it.wireValue)) }
        ManeuverModifier.entries.forEach { assertEquals(it, ManeuverModifier.parse(it.wireValue)) }
        assertNull(ManeuverModifier.parse(null))
        assertNull(ManeuverModifier.parse(""))
        for (text in listOf("", "TURN", "future", " turn")) assertFailsWith<IllegalArgumentException> { ManeuverType.parse(text) }
        for (text in listOf("LEFT", "future", " left")) assertFailsWith<IllegalArgumentException> { ManeuverModifier.parse(text) }
    }

    @Test
    fun allTypedStepsRoundTripThroughTheExistingRecordingFormat() {
        val modifiers = listOf(null) + ManeuverModifier.entries
        val steps = ManeuverType.entries.flatMap { type -> modifiers.map { Step(type, it, "Test, |~; road", geometryIndex = 0) } }
        val route = Route(listOf(GeoPoint(50.0, 30.0)), steps, 1.0)
        val encoded = RouteCodec.encode(route)
        assertTrue("turn~left~" in encoded)
        assertEquals(steps, RouteCodec.decode(encoded).steps)
        assertEquals(encoded, RouteCodec.encode(RouteCodec.decode(encoded)))
    }

    @Test
    fun unknownRecordedManeuversRejectOnlyTheirRouteEvent() {
        val route = Route(listOf(GeoPoint(50.0, 30.0)), listOf(Step(ManeuverType.TURN, ManeuverModifier.LEFT)), 1.0)
        val line = TripFormat.encode(TripEvent.RouteSet(10, route))
        for (invalid in listOf(line.replace("turn~", "future~"), line.replace("~left~", "~future~"))) {
            assertNull(TripFormat.decode(invalid))
            val events = TripFormat.read("$invalid\nX,20\n".byteInputStream())
            assertEquals(listOf(TripEvent.Stop(20)), events)
        }
    }

    @Test
    fun localizedGuidanceKeepsExistingText() {
        val left = Step(ManeuverType.TURN, ManeuverModifier.SLIGHT_LEFT, "Example")
        assertEquals("Turn slight left onto Example", EnglishPhrases.maneuver(left, null))
        assertEquals("Поверніть плавно ліворуч на Example", UkrainianPhrases.maneuver(left, null))
        assertEquals("Поверните плавно налево на Example", RussianPhrases.maneuver(left, null))
        assertEquals("Continue", EnglishPhrases.maneuver(Step(ManeuverType.CONTINUE), null))
        assertEquals("Make a U-turn", EnglishPhrases.maneuver(Step(ManeuverType.TURN, ManeuverModifier.UTURN), null))
        val roundabout = Step(ManeuverType.ROUNDABOUT, roundaboutExit = 2)
        assertEquals("At the roundabout take exit 2", EnglishPhrases.maneuver(roundabout, null))
    }
}
