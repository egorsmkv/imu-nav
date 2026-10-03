package org.imunav.app.search

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchCodecTest {
    @Test
    fun malformedEntriesDoNotDiscardValidHistory() {
        val valid = SearchResult(ResultKind.PLACE, "Київ", "", GeoPoint(50.0, 30.0), null, "recent")
        val row = RecentSearchCodec.encode(listOf(valid)).removePrefix("[").removeSuffix("]")
        val mixed = "[null, 7, {}, {\"k\":\"unknown\"}, $row]"
        assertEquals(listOf(valid), RecentSearchCodec.decode(mixed))
        assertEquals(emptyList<SearchResult>(), RecentSearchCodec.decode("broken"))
    }
}
