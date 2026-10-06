package org.imunav.core.util

/** Zero limits preserve logs until the user clears them; only completed files are pruned. */
data class LogRetention(val maxTotalBytes: Long = 0, val days: Int = 0) {
    init {
        require(maxTotalBytes >= 0)
        require(days in 0..3650)
    }
}

/** Disk usage of diagnostic text logs, excluding recordings and exported copies. */
data class LogStorage(val fileCount: Int, val bytes: Long)
