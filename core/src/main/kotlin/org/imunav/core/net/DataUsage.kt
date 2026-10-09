package org.imunav.core.net

/** A network-counter snapshot; null means unavailable, while zero means no traffic. */
data class DataUsage(val receivedBytes: Long?, val sentBytes: Long?) {
    companion object {
        /** Keeps unsupported platform counters out of byte formatting without hiding the other direction. */
        fun fromCounters(receivedBytes: Long, sentBytes: Long): DataUsage = DataUsage(
            receivedBytes = receivedBytes.takeIf { it >= 0 },
            sentBytes = sentBytes.takeIf { it >= 0 },
        )
    }
}
