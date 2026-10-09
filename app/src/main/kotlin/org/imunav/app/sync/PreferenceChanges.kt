package org.imunav.app.sync

/** A detached value, so changing the input maps cannot change an already prepared update. */
internal data class PreferenceChange(val key: String, val encodedValue: String)

/**
 * Select changed settings in incoming order, with travel mode last because applying it may start
 * route planning. JSON parsing and runtime setters remain in the effectful caller.
 */
internal fun preferenceChanges(current: Map<String, String>, incoming: Map<String, String>): List<PreferenceChange> = buildList {
    var travelMode: PreferenceChange? = null
    for ((key, value) in incoming) {
        if (!key.startsWith("setting:") || current[key] == value) continue
        val change = PreferenceChange(key, value)
        if (key == "setting:travel_mode") travelMode = change else add(change)
    }
    travelMode?.let { add(it) }
}
