package org.imunav.core.search

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressRow
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.PlaceRow
import org.imunav.core.search.SearchDb
import org.imunav.core.search.SearchResult
import org.imunav.core.search.StreetAddressRow
import org.imunav.core.search.StreetRow
import java.io.File
import java.io.StringWriter
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** JNI surface for immutable routing-pack address indexes. */
object TantivyNative {
    init {
        System.loadLibrary("imu_nav_search")
    }

    /** Desktop builder only; Android libraries intentionally omit the writer entry point. */
    external fun build(input: String, directory: String)
    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun begin(): Long
    external fun cancel(request: Long)
    external fun finish(request: Long)
    external fun search(handle: Long, request: Long, json: String): String
}

/** Request ownership allows cancellation from another thread without interrupting unrelated searches. */
class TantivyRequest : AutoCloseable {
    internal val handle = TantivyNative.begin()
    private val cancelled = AtomicBoolean()
    fun cancel() {
        cancelled.set(true)
        TantivyNative.cancel(handle)
    }
    internal fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Search cancelled")
    }
    override fun close() = TantivyNative.finish(handle)
}

/** One retrieval JNI call, followed by the production Kotlin matcher; close waits for resource readers. */
class TantivySearch(directory: File) : AutoCloseable {
    companion object {
        const val DIRECTORY = "search.tantivy"
        const val FORMAT = "tantivy-1"
    }

    private val lock = ReentrantReadWriteLock()
    private var handle = TantivyNative.open(directory.absolutePath)

    fun search(query: String, near: GeoPoint?, request: TantivyRequest, checkCancelled: () -> Unit = {}): List<SearchResult> = lock.read {
        check(handle != 0L) { "Search index closed" }
        request.checkCancelled()
        checkCancelled()
        val plan = AddressSearch.prepare(query)
        if (plan.words.isEmpty()) return@read emptyList()
        val payload = SearchIndexJson.encode(
            mapOf("words" to plan.words, "splits" to plan.splits.map { listOf(it.first, it.second) }, "number" to plan.number, "near" to near?.let { listOf(it.lat, it.lon) }),
        )
        val snapshot = SnapshotDb(SearchIndexJson.decode(TantivyNative.search(handle, request.handle, payload)))
        request.checkCancelled()
        checkCancelled()
        AddressSearch.search(snapshot, plan, near, checkCancelled = {
            request.checkCancelled()
            checkCancelled()
        })
    }

    override fun close() = lock.write {
        if (handle != 0L) TantivyNative.close(handle)
        handle = 0L
    }
}

/** JSON carries a complete candidate snapshot across JNI; requests never contain network data. */
object SearchIndexJson {
    private val factory = JsonFactory()
    fun encode(value: Any?): String = StringWriter().also { writer -> factory.createGenerator(writer).use { write(it, value) } }.toString()
    fun decode(text: String): Map<String, Any?> = factory.createParser(text).use { parser ->
        parser.nextToken()
        objectValue(parser)
    }

    private fun write(generator: JsonGenerator, value: Any?) {
        when (value) {
            null -> generator.writeNull()

            is String -> generator.writeString(value)

            is Number -> generator.writeNumber(value.toString())

            is Boolean -> generator.writeBoolean(value)

            is Map<*, *> -> {
                generator.writeStartObject()
                value.forEach { (key, item) ->
                    generator.writeFieldName(key.toString())
                    write(generator, item)
                }
                generator.writeEndObject()
            }

            is Iterable<*> -> {
                generator.writeStartArray()
                value.forEach { write(generator, it) }
                generator.writeEndArray()
            }

            else -> error("Unsupported search value")
        }
    }

    private fun objectValue(parser: JsonParser): Map<String, Any?> = buildMap {
        check(parser.currentToken == JsonToken.START_OBJECT)
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val name = parser.currentName()
            parser.nextToken()
            put(name, value(parser))
        }
    }

    private fun value(parser: JsonParser): Any? = when (parser.currentToken) {
        JsonToken.START_OBJECT -> objectValue(parser)
        JsonToken.START_ARRAY -> buildList { while (parser.nextToken() != JsonToken.END_ARRAY) add(value(parser)) }
        JsonToken.VALUE_STRING -> parser.text
        JsonToken.VALUE_NUMBER_INT -> parser.longValue
        JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
        JsonToken.VALUE_NULL -> null
        JsonToken.VALUE_TRUE -> true
        JsonToken.VALUE_FALSE -> false
        else -> error("Invalid search response")
    }
}

/** Snapshot lookups deliberately fail if native retrieval omitted a stage requested by Kotlin. */
private class SnapshotDb(private val snapshot: Map<String, Any?>) : SearchDb {
    // The internal protocol is generated by our Rust library and validated structurally by the parser.
    @Suppress("UNCHECKED_CAST")
    private fun objects(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
    private fun Map<String, Any?>.long(key: String): Long = (getValue(key) as Number).toLong()
    private fun Map<String, Any?>.double(key: String): Double = (getValue(key) as Number).toDouble()
    private fun Map<String, Any?>.string(key: String): String = getValue(key) as String
    private fun rows(kind: String, match: String, limit: Int, ids: Collection<Long> = emptyList()): List<Map<String, Any?>> {
        val lookup = objects(snapshot[kind]).first { lookup ->
            val words = lookup.getValue("words") as List<*>
            words.joinToString(" ") { it.toString().replace("'", "") + "*" } == match && lookup.long("limit") == limit.toLong() &&
                (lookup["ids"] as List<*>).map { (it as Number).toLong() }.sorted() == ids.sorted()
        }
        return objects(lookup["rows"])
    }

    override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> = rows("places", match, limit).map {
        PlaceRow(it.long("id"), it.string("name"), it.string("place_kind"), it.double("lat"), it.double("lon"), it.long("population").toInt())
    }
    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?, near: GeoPoint?): List<StreetRow> = rows("streets", match, limit, placeIds.orEmpty()).map {
        StreetRow(it.long("id"), it.string("name"), (it["place_id"] as? Number)?.toLong(), it["place_name"] as? String, it.double("lat"), it.double("lon"))
    }
    override fun addresses(streetId: Long, number: String): List<AddressRow> = batchAddresses(listOf(streetId), number).map { AddressRow(it.number, it.lat, it.lon) }
    override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> = objects(snapshot["addresses"]).filter {
        it.long("place_id") in streetIds && (it.string("number") == number || it.string("number").startsWith("$number "))
    }.map { StreetAddressRow(it.long("place_id"), it.string("number"), it.double("lat"), it.double("lon")) }
}
