package org.imunav.core.net

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import java.io.StringWriter

/** Streaming boundaries share syntax checks without retaining untyped trees or private payloads in errors. */
object WireJson {
    private val factory = JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    /** Decode exactly one value; parser diagnostics can contain private input, so discard their causes. */
    fun <T> decode(text: String, read: JsonParser.() -> T): T = try {
        factory.createParser(text).use { parser ->
            require(parser.nextToken() != null)
            val result = parser.read()
            require(parser.nextToken() == null)
            result
        }
    } catch (_: JsonProcessingException) {
        throw IllegalArgumentException("Invalid JSON payload")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid JSON payload")
    }

    /** Write protocol fields directly, without a heterogeneous intermediate object tree. */
    fun encode(write: JsonGenerator.() -> Unit): String = StringWriter().also { writer ->
        factory.createGenerator(writer).use { it.write() }
    }.toString()
}

/** Visit fields with the parser on each value, rejecting premature EOF and duplicate field names. */
fun JsonParser.fields(read: (String) -> Unit) {
    require(currentToken == JsonToken.START_OBJECT)
    while (nextToken() != JsonToken.END_OBJECT) {
        require(currentToken == JsonToken.FIELD_NAME)
        val name = currentName()
        require(nextToken() != null)
        read(name)
    }
}

/** Every element reader must consume its complete value; a missing closing bracket is invalid. */
fun <T> JsonParser.elements(read: JsonParser.() -> T): List<T> {
    require(currentToken == JsonToken.START_ARRAY)
    return buildList {
        while (nextToken() != JsonToken.END_ARRAY) {
            require(currentToken != null)
            add(read())
        }
    }
}

/** Protocol strings must not be synthesized from numbers or other token types. */
fun JsonParser.textValue(): String {
    require(currentToken == JsonToken.VALUE_STRING)
    return text
}

/** Integer tokens must fit the target domain, rather than being truncated from a decimal or large integer. */
fun JsonParser.integerValue(minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
    require(currentToken == JsonToken.VALUE_NUMBER_INT)
    return longValue.also { require(it in minimum..maximum) }
}

/** Check Int bounds before narrowing the parser's integer representation. */
fun JsonParser.intValueChecked(minimum: Int = Int.MIN_VALUE): Int = integerValue(minimum.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** Reject overflow to infinity before coordinates and measurements enter domain models. */
fun JsonParser.finiteValue(): Double {
    require(currentToken == JsonToken.VALUE_NUMBER_INT || currentToken == JsonToken.VALUE_NUMBER_FLOAT)
    return doubleValue.also { require(it.isFinite()) }
}

/** Strings such as "false" must not acquire Boolean semantics through coercion. */
fun JsonParser.booleanValueChecked(): Boolean {
    require(currentToken == JsonToken.VALUE_TRUE || currentToken == JsonToken.VALUE_FALSE)
    return booleanValue
}

/** JSON null is distinct from malformed values of an optional field. */
fun <T> JsonParser.nullable(read: JsonParser.() -> T): T? = if (currentToken == JsonToken.VALUE_NULL) null else read()
