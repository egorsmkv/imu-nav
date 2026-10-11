package org.imunav.core.util

import java.util.Locale

/** A presentation of a diagnostic line; [raw] always retains the exact text for search and export. */
data class LogEntry(val raw: String, val timestamp: String, val elapsedMs: Long?, val message: String) {
    /** Keep the existing diagnostic keyword categories; these are hints, not actual severity levels. */
    val isProblem: Boolean = PROBLEM_WORDS.any(message.lowercase(Locale.US)::contains)
    val isPosition: Boolean = POSITION_WORDS.any(message.lowercase(Locale.US)::contains)
    val isNavigation: Boolean = NAVIGATION_WORDS.any(message.lowercase(Locale.US)::contains)

    companion object {
        private val prefix = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[(\\d+)] (.*)$", RegexOption.DOT_MATCHES_ALL)
        private val PROBLEM_WORDS = listOf("fail", "error", "reject", "lost", "off_route", "discard", "spoof")
        private val POSITION_WORDS = listOf("gps", "cell", "signal", "compass", "position", "agps", "net_")
        private val NAVIGATION_WORDS = listOf("nav", "route", "turn", "trip", "blind", "reroute", "manual_start", "start_accuracy")

        /** Only strip a complete TripLog prefix; brackets inside an unprefixed message are data. */
        fun parse(line: String): LogEntry {
            val match = prefix.matchEntire(line) ?: return LogEntry(line, "", null, line)
            return LogEntry(line, match.groupValues[1], match.groupValues[2].toLongOrNull(), match.groupValues[3])
        }
    }
}
