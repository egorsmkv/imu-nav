package org.imunav.core

import org.imunav.core.search.AddressSearch
import org.junit.Test
import kotlin.test.assertEquals

/** Parsing must not turn an indexed house suffix into an extra street/settlement interpretation. */
class SearchPlanTest {
    @Test
    fun spacedLettersStayWithTheHouseBeforeStopWordsAreRemoved() {
        for (number in listOf("4 в", "181 а", "7 с", "8 г", "9 м", "10 c", "12/2 а", "4 в к1")) {
            val plan = AddressSearch.prepare("Львів, вул. Топольна $number")
            assertEquals(listOf("львів", "топольна"), plan.words, number)
            assertEquals(number, plan.number)
        }
        assertEquals("4 в", AddressSearch.prepare("Топольна 4-В").number)
        assertEquals("4 в", AddressSearch.prepare("Топольна 4   В").number)
    }

    @Test
    fun existingNumbersAndNonHouseWordsKeepTheirInterpretation() {
        for (number in listOf("22", "220", "22а", "22/2а", "22 к1", "22a k1", "22а к1")) {
            val plan = AddressSearch.prepare("вул. Топольна $number")
            assertEquals(listOf("топольна"), plan.words)
            assertEquals(number, plan.number)
        }
        assertEquals(null, AddressSearch.prepare("Топольна в").number)
        assertEquals(null, AddressSearch.prepare("Топольна 22 корпус").number)
        assertEquals("22", AddressSearch.prepare("Main 22 street").number)
        assertEquals(listOf("main"), AddressSearch.prepare("Main 22 street").words)
        assertEquals(null, AddressSearch.prepare("").number)
        assertEquals(emptyList(), AddressSearch.prepare("22").words)
    }
}
