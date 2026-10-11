package org.imunav.core.diagnostics

import org.imunav.core.util.BufferStats
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Bounds retained payloads including the writer's current entry; failure closes admission and drops waiting input. */
class CaptureQueue<T>(private val maxItems: Int, private val maxBytes: Long) {
    private data class Entry<T>(val value: T, val weight: Long)
    private val queue = ArrayBlockingQueue<Entry<T>>(maxItems)
    private var accepting = true
    private var items = 0
    private var bytes = 0L
    private var peakItems = 0
    private var peakBytes = 0L
    private var rejected = 0L

    init {
        require(maxItems > 0 && maxBytes > 0)
    }

    val stats: BufferStats
        @Synchronized get() = BufferStats(items, bytes, peakItems, peakBytes, rejected)

    @Synchronized fun offer(value: T, weight: Long): Boolean {
        require(weight > 0)
        if (!accepting || items >= maxItems || weight > maxBytes - bytes) {
            rejected++
            return false
        }
        items++
        bytes += weight
        check(queue.offer(Entry(value, weight)))
        peakItems = maxOf(peakItems, items)
        peakBytes = maxOf(peakBytes, bytes)
        return true
    }

    /** Release ownership in finally even when serialization or storage throws. */
    fun consume(timeoutMs: Long, write: (T) -> Unit): Boolean {
        val entry = queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
        try {
            write(entry.value)
        } finally {
            synchronized(this) {
                items--
                bytes -= entry.weight
            }
        }
        return true
    }

    /** Normal stop drains the accepted prefix; failure discards waiting entries and returns their count. */
    @Synchronized fun close(discard: Boolean = false): Int {
        accepting = false
        var dropped = 0
        if (discard) {
            while (true) {
                val entry = queue.poll() ?: break
                items--
                bytes -= entry.weight
                dropped++
            }
        }
        return dropped
    }
}
