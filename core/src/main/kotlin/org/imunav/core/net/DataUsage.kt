package org.imunav.core.net

/** A network-counter snapshot; null means unavailable, while zero means no traffic. */
data class DataUsage(val receivedBytes: Long?, val sentBytes: Long?) {
    /** Never present a partial or overflowing sum as the total. */
    val totalBytes: Long?
        get() {
            val received = receivedBytes ?: return null
            val sent = sentBytes ?: return null
            return if (received >= 0 && sent >= 0 && received <= Long.MAX_VALUE - sent) received + sent else null
        }

    /** A missing or empty total has no meaningful traffic split. */
    val receivedFraction: Float?
        get() {
            val total = totalBytes?.takeIf { it > 0 } ?: return null
            return receivedBytes?.let { (it.toDouble() / total).toFloat() }
        }

    companion object {
        /** Keeps unsupported platform counters out of byte formatting without hiding the other direction. */
        fun fromCounters(receivedBytes: Long, sentBytes: Long): DataUsage = DataUsage(
            receivedBytes = receivedBytes.takeIf { it >= 0 },
            sentBytes = sentBytes.takeIf { it >= 0 },
        )
    }
}
