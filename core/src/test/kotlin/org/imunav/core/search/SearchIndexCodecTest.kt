package org.imunav.core.search

import org.imunav.core.geo.GeoPoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic native payloads exercise boundary checks without loading a JNI library. */
class SearchIndexCodecTest {
    private val place = """{"kind":"place","id":1,"name":"Town","place_kind":"town","population":100,"lat":50,"lon":30}"""
    private val street = """{"kind":"street","id":2,"name":"Main","place_id":null,"place_name":null,"lat":50,"lon":30}"""
    private val address = """{"kind":"address","id":3,"place_id":2,"number":"22","lat":50,"lon":30}"""

    private fun snapshot(placeRow: String = place, streetRow: String = street, addressRows: String = address): String = """
        {"places":[{"words":["town"],"limit":10,"ids":[],"rows":[$placeRow],"count":null}],
         "streets":[{"words":["main"],"limit":20,"ids":[5,4],"rows":[$streetRow],"count":1}],"addresses":[$addressRows]}
    """.trimIndent()

    @Test
    fun requestsRetainTheNativeArrayAndNullShapes() {
        assertEquals(
            """{"words":["main"],"splits":[],"number":null,"near":[50.0,30.0]}""",
            SearchIndexCodec.request(SearchIndexRequest(SearchPlan(listOf("main"), null), GeoPoint(50.0, 30.0))),
        )
        assertTrue(SearchIndexCodec.request(SearchIndexRequest(SearchPlan(listOf("new", "town"), "22"), null)).contains("\"splits\":[[[\"new\"],[\"town\"]]"))
        assertTrue(SearchIndexCodec.request(SearchIndexRequest(SearchPlan(emptyList(), null), null)).endsWith("\"near\":null}"))
    }

    @Test
    fun typedLookupsPreserveIdentityNullsAndAddressFiltering() {
        val part = address.replace("\"22\"", "\"22 к1\"")
        val other = address.replace("\"22\"", "\"220\"")
        val parsed = SearchIndexCodec.snapshot(snapshot(addressRows = "$address,$part,$other"))
        assertNull(parsed.streets.single().rows.single().placeId)
        assertNull(parsed.streets.single().rows.single().placeName)
        val db = SnapshotDb(parsed)
        assertEquals("Town", db.places("town*", 10).single().name)
        assertEquals("Main", db.streets("main*", 20, listOf(4, 5)).single().name)
        assertEquals(listOf("22", "22 к1"), db.batchAddresses(listOf(2), "22").map { it.number })
        assertTrue(db.batchAddresses(listOf(9), "22").isEmpty())
        assertFailsWith<NoSuchElementException> { db.places("missing*", 10) }
        assertFailsWith<NoSuchElementException> { db.streets("main*", 20, listOf(4)) }
        assertTrue(SearchIndexCodec.snapshot("""{"places":[],"streets":[],"addresses":[]}""").places.isEmpty())
    }

    @Test
    fun malformedValuesNeverReachTypedRowsOrLeakPayloadsInErrors() {
        val invalid = listOf(
            "{}", "[]", snapshot().dropLast(2), snapshot() + "{}",
            snapshot().replace("\"places\":", "\"places\":[],\"places\":"),
            snapshot().replace("\"limit\":10", "\"limit\":1.5"),
            snapshot().replace("\"limit\":10", "\"limit\":2147483648"),
            snapshot().replace("\"ids\":[5,4]", "\"ids\":[\"5\"]"),
            snapshot(placeRow = place.replace("\"id\":1", "\"id\":9223372036854775808")),
            snapshot(placeRow = place.replace("\"population\":100", "\"population\":2147483648")),
            snapshot(placeRow = place.replace("\"name\":\"Town\"", "\"name\":false")),
            snapshot(placeRow = place.replace("\"lat\":50", "\"lat\":1e999")),
            snapshot(streetRow = street.replace("\"place_id\":null", "\"place_id\":\"1\"")),
            snapshot(streetRow = street.replace("\"lon\":30", "\"lon\":181")),
            snapshot(addressRows = address.replace("\"place_id\":2,", "")),
        )
        invalid.forEach { text ->
            val failure = assertFailsWith<IllegalArgumentException> { SearchIndexCodec.snapshot(text) }
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("Town"))
        }
    }
}
