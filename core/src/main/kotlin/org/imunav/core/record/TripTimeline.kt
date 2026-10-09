package org.imunav.core.record

/** An input with its delivery time; [event] retains the original sensor measurement timestamp. */
data class ScheduledTripEvent(val event: TripEvent, val elapsedMs: Long)

/** Selects arrival-aware scheduling without changing the historic ordering of legacy recordings. */
object TripTimeline {
    fun schedule(events: List<TripEvent>, legacyFileOrder: Boolean = false): List<ScheduledTripEvent> {
        if (events.none { it.arrivalElapsedMs != null }) {
            val ordered = if (legacyFileOrder) events else events.sortedBy { it.elapsedMs }
            return ordered.map { ScheduledTripEvent(it, it.elapsedMs) }
        }
        // Preserve file order, including ties and legacy prefixes after repair/append. Missing or
        // regressing arrival times cannot rewind replay; measurement time is only a fallback.
        var previous = Long.MIN_VALUE
        return events.map { event ->
            val time = maxOf(previous, event.arrivalElapsedMs ?: event.elapsedMs)
            previous = time
            ScheduledTripEvent(event, time)
        }
    }
}
