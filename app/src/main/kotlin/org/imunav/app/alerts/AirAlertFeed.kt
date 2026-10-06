package org.imunav.app.alerts

/** One accepted server update; a snapshot never asks Android to post a notification. */
internal data class AirAlertFeedUpdate(
    val active: List<AirAlertRegion>,
    val stale: Boolean,
    val started: List<AirAlertRegion> = emptyList(),
    val cleared: List<AirAlertRegion> = emptyList(),
)

/**
 * Applies the ordered alert stream for one WebSocket connection. Server sequence numbers prevent
 * queued or repeated messages from notifying for a change already included in a snapshot.
 */
internal class AirAlertFeed {
    private var lastSequence: Long? = null
    private var active = emptyList<AirAlertRegion>()

    /** Replace displayed status without treating existing alerts as new starts. */
    fun snapshot(sequence: Long, regions: List<AirAlertRegion>, stale: Boolean): AirAlertFeedUpdate? {
        if (sequence < 0 || lastSequence?.let { sequence <= it } == true) return null
        lastSequence = sequence
        active = regions.distinctBy { it.id }
        return AirAlertFeedUpdate(active, stale)
    }

    /** Apply only newer changes after the first snapshot, suppressing duplicate region events. */
    fun changes(sequence: Long, started: List<AirAlertRegion>, cleared: List<AirAlertRegion>): AirAlertFeedUpdate? {
        val priorSequence = lastSequence ?: return null
        if (sequence <= priorSequence) return null
        val next = active.associateBy { it.id }.toMutableMap()
        val actualCleared = cleared.filter { next.remove(it.id) != null }
        val actualStarted = started.filter { next.put(it.id, it) == null }
        lastSequence = sequence
        active = next.values.toList()
        return AirAlertFeedUpdate(active, false, actualStarted, actualCleared)
    }
}

/** Title choice for one Android notification. */
internal enum class AirAlertNoticeKind { START, CLEAR, UPDATE }

/** Short, language-selected region list; Android supplies the localized title and truncation suffix. */
internal data class AirAlertNotice(val kind: AirAlertNoticeKind, val names: String, val truncated: Boolean)

/** Build a notification preview only for actual starts or all-clears. */
internal fun airAlertNotice(started: List<AirAlertRegion>, cleared: List<AirAlertRegion>, english: Boolean, maxNames: Int): AirAlertNotice? {
    if (started.isEmpty() && cleared.isEmpty()) return null
    val kind = when {
        started.isNotEmpty() && cleared.isNotEmpty() -> AirAlertNoticeKind.UPDATE
        started.isNotEmpty() -> AirAlertNoticeKind.START
        else -> AirAlertNoticeKind.CLEAR
    }
    val regions = (started + cleared).distinctBy { it.id }
    val shown = regions.take(maxNames)
    val names = shown.joinToString(", ") { if (english) it.nameEn else it.nameUk }
    return AirAlertNotice(kind, names, truncated = regions.size > shown.size)
}
