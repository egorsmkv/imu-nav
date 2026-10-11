package org.imunav.core.util

import java.util.concurrent.Executor

/** A snapshot of admitted payloads, including the command currently executing. Bytes are estimates, not JVM heap measurements. */
data class BufferStats(val items: Int, val bytes: Long, val peakItems: Int, val peakBytes: Long, val rejected: Long)

/**
 * Bounds payload ownership without running storage work on producers. Reserved controls keep close commands
 * admissible even during overload. One drain preserves FIFO ordering on the supplied serial executor.
 */
class OrderedBuffer(private val worker: Executor, private val maxItems: Int, private val maxBytes: Long, private val maxControls: Int = 64) {
    private data class Entry(val owner: Any?, val weight: Long, val action: () -> Unit)
    private val pending = ArrayDeque<Entry>()
    private var draining = false
    private var items = 0
    private var bytes = 0L
    private var controls = 0
    private var peakItems = 0
    private var peakBytes = 0L
    private var rejected = 0L

    init {
        require(maxItems > 0 && maxBytes > 0 && maxControls > 0)
    }

    val stats: BufferStats
        @Synchronized get() = BufferStats(items, bytes, peakItems, peakBytes, rejected)

    /** Reserves all lifecycle commands before accepting a session; unused reservations must be released. */
    @Synchronized fun reserve(count: Int): Reservation? {
        require(count > 0)
        if (count > maxControls - controls) return null
        controls += count
        return Reservation(count)
    }

    /** Rejects new payloads without blocking; the caller chooses whether to drop or stop its stream. */
    @Synchronized fun offer(owner: Any, weight: Long, action: () -> Unit): Boolean {
        require(weight > 0)
        if (items >= maxItems || weight > maxBytes - bytes) {
            rejected++
            return false
        }
        items++
        bytes += weight
        peakItems = maxOf(peakItems, items)
        peakBytes = maxOf(peakBytes, bytes)
        enqueue(Entry(owner, weight, action))
        return true
    }

    /** Releases waiting payloads for a failed owner; an executing command retains its charge until return. */
    @Synchronized fun discard(owner: Any) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.owner === owner) {
                iterator.remove()
                items--
                bytes -= entry.weight
            }
        }
    }

    /** Reservation state shares the buffer lock so producers cannot spend a close slot twice. */
    inner class Reservation internal constructor(private var remaining: Int) {
        fun submit(action: () -> Unit) = synchronized(this@OrderedBuffer) {
            check(remaining > 0)
            remaining--
            enqueue(Entry(null, 0, action))
        }

        fun release() = synchronized(this@OrderedBuffer) {
            controls -= remaining
            remaining = 0
        }
    }

    private fun enqueue(entry: Entry) {
        pending.addLast(entry)
        if (!draining) {
            draining = true
            worker.execute(::drain)
        }
    }

    /** Charges are released in finally, including when a command fails. Remaining work is rescheduled on exception. */
    private fun drain() {
        var completedNormally = false
        try {
            while (true) {
                val entry = synchronized(this) {
                    if (pending.isEmpty()) {
                        draining = false
                        completedNormally = true
                        return
                    }
                    pending.removeFirst()
                }
                try {
                    entry.action()
                } finally {
                    synchronized(this) {
                        if (entry.weight == 0L) {
                            controls--
                        } else {
                            items--
                            bytes -= entry.weight
                        }
                    }
                }
            }
        } finally {
            if (!completedNormally) {
                synchronized(this) {
                    draining = false
                    if (pending.isNotEmpty()) {
                        draining = true
                        worker.execute(::drain)
                    }
                }
            }
        }
    }
}
