package org.imunav.search

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.PlaceRow
import org.imunav.core.search.SearchDb
import org.imunav.core.search.StreetAddressRow
import org.imunav.core.search.StreetRow

/** Count logical SQLite lookups outside timed samples; a JNI call is not the same as a native index lookup. */
class CountingSearchDb(private val delegate: SearchDb) : SearchDb by delegate {
    var calls: Int = 0
        private set
    var houseCalls: Int = 0
        private set

    override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> {
        calls++
        return delegate.places(match, limit, near)
    }

    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?, near: GeoPoint?): List<StreetRow> {
        calls++
        return delegate.streets(match, limit, placeIds, near)
    }

    override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> {
        calls++
        houseCalls++
        return delegate.batchAddresses(streetIds, number)
    }
}
