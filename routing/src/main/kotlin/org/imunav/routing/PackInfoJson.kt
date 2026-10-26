package org.imunav.routing

import org.imunav.core.net.WireJson
import org.imunav.core.net.booleanValueChecked
import org.imunav.core.net.elements
import org.imunav.core.net.fields
import org.imunav.core.net.finiteValue
import org.imunav.core.net.integerValue
import org.imunav.core.net.nullable
import org.imunav.core.net.textValue

/** Typed metadata keeps legacy defaults while rejecting malformed present fields before opening a pack. */
internal object PackInfoJson {
    fun encode(info: PackInfo): String = WireJson.encode {
        writeStartObject()
        writeStringField("name", info.name)
        writeStringField("builtAt", info.builtAt)
        writeStringField("source", info.source)
        writeArrayFieldStart("bounds")
        info.bounds.forEach { writeNumber(it) }
        writeEndArray()
        writeNumberField("sizeBytes", info.sizeBytes)
        writeArrayFieldStart("profiles")
        info.profiles.forEach { writeString(it) }
        writeEndArray()
        writeBooleanField("elevation", info.elevation)
        writeBooleanField("turnCosts", info.turnCosts)
        writeStringField("searchFormat", info.searchFormat)
        writeStringField("routingFormat", info.routingFormat)
        writeEndObject()
    }

    fun decode(text: String): PackInfo = WireJson.decode(text) {
        var name: String? = null
        var builtAt = ""
        var source = ""
        var bounds: List<Double>? = null
        var sizeBytes = 0L
        var profiles = listOf("car")
        var elevation = false
        var turnCosts = false
        var searchFormat: String? = null
        var routingFormat: String? = null
        fields { field ->
            when (field) {
                "name" -> name = textValue()
                "builtAt" -> builtAt = nullable { textValue() }.orEmpty()
                "source" -> source = nullable { textValue() }.orEmpty()
                "bounds" -> bounds = elements { finiteValue() }
                "sizeBytes" -> sizeBytes = nullable { integerValue(0) } ?: 0
                "profiles" -> profiles = nullable { elements { textValue() } } ?: listOf("car")
                "elevation" -> elevation = nullable { booleanValueChecked() } ?: false
                "turnCosts" -> turnCosts = nullable { booleanValueChecked() } ?: false
                "searchFormat" -> searchFormat = nullable { textValue() }
                "routingFormat" -> routingFormat = nullable { textValue() }
                else -> skipChildren()
            }
        }
        val box = requireNotNull(bounds)
        require(box.size == 4)
        require(box[0] in -90.0..90.0 && box[1] in box[0]..90.0 && box[2] in -180.0..180.0 && box[3] in box[2]..180.0)
        require(profiles.isNotEmpty() && profiles.distinct() == profiles && profiles.all { it == "car" || it == "foot" })
        PackInfo(requireNotNull(name), builtAt, source, box.toDoubleArray(), sizeBytes, profiles, elevation, turnCosts, searchFormat, routingFormat)
    }
}
