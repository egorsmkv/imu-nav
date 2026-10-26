package org.imunav.core.search

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import org.imunav.core.geo.GeoPoint
import org.imunav.core.net.WireJson
import org.imunav.core.net.elements
import org.imunav.core.net.fields
import org.imunav.core.net.finiteValue
import org.imunav.core.net.intValueChecked
import org.imunav.core.net.integerValue
import org.imunav.core.net.nullable
import org.imunav.core.net.textValue

/** The native retriever consumes the same plan that Kotlin subsequently ranks. */
data class SearchIndexRequest(val plan: SearchPlan, val near: GeoPoint?)

/** Each retrieval stage retains its lookup identity even when it returned no rows. */
data class SearchLookup<T>(val words: List<String>, val limit: Int, val ids: List<Long>, val rows: List<T>)

/** A complete response is decoded before any candidate is used by the matcher. */
data class SearchSnapshot(val places: List<SearchLookup<PlaceRow>>, val streets: List<SearchLookup<StreetRow>>, val addresses: List<StreetAddressRow>)

/** Export variants prevent mixing place, street and address-only fields in the host builder. */
sealed interface SearchIndexRow {
    /** Place aliases accompany the display row only while building an index. */
    data class Place(val row: PlaceRow, val text: String) : SearchIndexRow

    /** A street may have no parent settlement, without losing its search aliases. */
    data class Street(val row: StreetRow, val text: String) : SearchIndexRow

    /** The export ID identifies the address; its parent ID identifies the street. */
    data class Address(val id: Long, val row: StreetAddressRow) : SearchIndexRow
}

/** Explicit JSON preserves the Rust protocol without exposing arbitrary maps to callers. */
object SearchIndexCodec {
    fun request(request: SearchIndexRequest): String = WireJson.encode {
        writeStartObject()
        writeArrayFieldStart("words")
        request.plan.words.forEach { writeString(it) }
        writeEndArray()
        writeArrayFieldStart("splits")
        request.plan.splits.forEach { (place, street) ->
            writeStartArray()
            for (words in listOf(place, street)) {
                writeStartArray()
                words.forEach { writeString(it) }
                writeEndArray()
            }
            writeEndArray()
        }
        writeEndArray()
        writeStringField("number", request.plan.number)
        writeFieldName("near")
        request.near?.let { point ->
            require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
            writeStartArray()
            writeNumber(point.lat)
            writeNumber(point.lon)
            writeEndArray()
        } ?: writeNull()
        writeEndObject()
    }

    fun snapshot(text: String): SearchSnapshot = WireJson.decode(text) {
        var places: List<SearchLookup<PlaceRow>>? = null
        var streets: List<SearchLookup<StreetRow>>? = null
        var addresses: List<StreetAddressRow>? = null
        fields { name ->
            when (name) {
                "places" -> places = elements { lookup { placeRow() } }
                "streets" -> streets = elements { lookup { streetRow() } }
                "addresses" -> addresses = elements { addressRow() }
                else -> skipChildren()
            }
        }
        SearchSnapshot(requireNotNull(places), requireNotNull(streets), requireNotNull(addresses))
    }

    fun row(value: SearchIndexRow): String = WireJson.encode {
        writeStartObject()
        when (value) {
            is SearchIndexRow.Place -> value.row.let {
                identity("place", it.id, it.lat, it.lon)
                writeStringField("name", it.name)
                writeStringField("place_kind", it.kind)
                writeNumberField("population", it.population)
                writeStringField("text", value.text)
            }

            is SearchIndexRow.Street -> value.row.let {
                identity("street", it.id, it.lat, it.lon)
                writeStringField("name", it.name)
                writeFieldName("place_id")
                it.placeId?.let { id ->
                    require(id > 0)
                    writeNumber(id)
                } ?: writeNull()
                writeStringField("place_name", it.placeName)
                writeStringField("text", value.text)
            }

            is SearchIndexRow.Address -> value.row.let {
                identity("address", value.id, it.lat, it.lon)
                require(it.streetId > 0)
                writeNumberField("place_id", it.streetId)
                writeStringField("number", it.number)
            }
        }
        writeEndObject()
    }

    private fun JsonGenerator.identity(kind: String, id: Long, lat: Double, lon: Double) {
        require(id > 0 && lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
        writeStringField("kind", kind)
        writeNumberField("id", id)
        writeNumberField("lat", lat)
        writeNumberField("lon", lon)
    }

    private fun <T> JsonParser.lookup(readRow: JsonParser.() -> T): SearchLookup<T> {
        var words: List<String>? = null
        var limit: Int? = null
        var ids: List<Long>? = null
        var rows: List<T>? = null
        fields { name ->
            when (name) {
                "words" -> words = elements { textValue() }
                "limit" -> limit = intValueChecked(0)
                "ids" -> ids = elements { integerValue(1) }
                "rows" -> rows = elements(readRow)
                "count" -> nullable { integerValue(0) }
                else -> skipChildren()
            }
        }
        return SearchLookup(requireNotNull(words), requireNotNull(limit), requireNotNull(ids), requireNotNull(rows))
    }

    private fun JsonParser.placeRow(): PlaceRow = when (val value = indexRow()) {
        is SearchIndexRow.Place -> value.row
        else -> throw IllegalArgumentException("Invalid search row kind")
    }

    private fun JsonParser.streetRow(): StreetRow = when (val value = indexRow()) {
        is SearchIndexRow.Street -> value.row
        else -> throw IllegalArgumentException("Invalid search row kind")
    }

    private fun JsonParser.addressRow(): StreetAddressRow = when (val value = indexRow()) {
        is SearchIndexRow.Address -> value.row
        else -> throw IllegalArgumentException("Invalid search row kind")
    }

    /** Rust emits a row union; select a typed variant only after checking each field's token and range. */
    private fun JsonParser.indexRow(): SearchIndexRow {
        var kind: String? = null
        var id: Long? = null
        var lat: Double? = null
        var lon: Double? = null
        var name: String? = null
        var placeKind: String? = null
        var population: Int? = null
        var placeId: Long? = null
        var placeName: String? = null
        var number: String? = null
        var text = ""
        fields { field ->
            when (field) {
                "kind" -> kind = textValue()
                "id" -> id = integerValue(1)
                "lat" -> lat = finiteValue().also { require(it in -90.0..90.0) }
                "lon" -> lon = finiteValue().also { require(it in -180.0..180.0) }
                "name" -> name = textValue()
                "place_kind" -> placeKind = textValue()
                "population" -> population = intValueChecked()
                "place_id" -> placeId = nullable { integerValue(1) }
                "place_name" -> placeName = nullable { textValue() }
                "number" -> number = textValue()
                "text" -> text = textValue()
                else -> skipChildren()
            }
        }
        val rowId = requireNotNull(id)
        val latitude = requireNotNull(lat)
        val longitude = requireNotNull(lon)
        return when (kind) {
            "place" -> SearchIndexRow.Place(PlaceRow(rowId, requireNotNull(name), requireNotNull(placeKind), latitude, longitude, requireNotNull(population)), text)
            "street" -> SearchIndexRow.Street(StreetRow(rowId, requireNotNull(name), placeId, placeName, latitude, longitude), text)
            "address" -> SearchIndexRow.Address(rowId, StreetAddressRow(requireNotNull(placeId), requireNotNull(number), latitude, longitude))
            else -> throw IllegalArgumentException("Invalid search row kind")
        }
    }
}

/** Missing retrieval stages remain failures; an empty stage is a valid lookup with no rows. */
internal class SnapshotDb(private val snapshot: SearchSnapshot) : SearchDb {
    private fun <T> rows(lookups: List<SearchLookup<T>>, match: String, limit: Int, ids: Collection<Long> = emptyList()): List<T> = lookups.first { lookup ->
        lookup.words.joinToString(" ") { it.replace("'", "") + "*" } == match && lookup.limit == limit && lookup.ids.sorted() == ids.sorted()
    }.rows

    override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> = rows(snapshot.places, match, limit)
    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?, near: GeoPoint?): List<StreetRow> = rows(snapshot.streets, match, limit, placeIds.orEmpty())
    override fun addresses(streetId: Long, number: String): List<AddressRow> = batchAddresses(listOf(streetId), number).map { AddressRow(it.number, it.lat, it.lon) }
    override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> = snapshot.addresses.filter {
        it.streetId in streetIds && (it.number == number || it.number.startsWith("$number "))
    }
}
