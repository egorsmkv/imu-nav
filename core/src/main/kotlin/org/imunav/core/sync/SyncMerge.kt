package org.imunav.core.sync

/** One server-owned version; a null payload is a deletion marker, never an absent record. */
data class SyncEntry(val kind: String, val key: String, val revision: Long, val payload: String?) {
    val identity: String get() = "$kind:$key"
}

/** Compare durable local data to the last acknowledged snapshot without relying on phone clocks. */
object SyncMerge {
    /** First restore lets the server win existing IDs; missing local entries are uploaded once. */
    fun changes(local: Map<String, String>, baseline: List<SyncEntry>, remote: List<SyncEntry>, firstRestore: Boolean): List<SyncEntry> {
        val previous = baseline.associateBy { it.identity }
        val current = remote.associateBy { it.identity }
        return (local.keys + previous.keys).mapNotNull { identity ->
            val old = previous[identity]
            val value = local[identity]
            val cloud = current[identity]
            when {
                firstRestore && cloud != null -> null
                value == old?.payload -> null
                else -> SyncEntry(identity.substringBefore(':'), identity.substringAfter(':'), old?.revision ?: 0, value)
            }
        }
    }

    /** Overlay edits made during a request so acknowledging that request cannot erase newer work. */
    fun apply(remote: List<SyncEntry>, sent: Map<String, String>, now: Map<String, String>): Map<String, String> {
        val result = remote.mapNotNull { entry -> entry.payload?.let { entry.identity to it } }.toMap().toMutableMap()
        val deleted = remote.filter { it.payload == null }.map { it.identity }.toSet()
        (sent.keys + now.keys).filter { sent[it] != now[it] && it !in deleted }.forEach { key ->
            now[key]?.let { result[key] = it } ?: result.remove(key)
        }
        return result
    }
}
