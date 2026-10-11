package org.imunav.core.diagnostics

import org.imunav.core.util.OrderedBuffer

/** Persistent owner is independent of email, tokens and the currently selected account. */
data class DiagnosticOwner(val server: String, val accountId: Long, val syncIdentity: String) {
    init {
        require(server.isNotBlank() && accountId > 0 && syncIdentity.isNotBlank())
    }
}

/**
 * A session shares global item/byte limits and reserves its lifecycle before admitting payloads.
 * Overload stops capture, preserving an accepted prefix; finish receives the omitted count.
 */
class DiagnosticCapture private constructor(private val buffer: OrderedBuffer, private val controls: OrderedBuffer.Reservation, private val onOverload: () -> Unit) {
    private var accepting = true
    private var finished = false
    private var dropped = 0L

    @Synchronized fun offer(weight: Long, action: () -> Unit): Boolean {
        if (finished) return false
        if (!accepting || !buffer.offer(this, weight, action)) {
            if (accepting) controls.submit(onOverload)
            accepting = false
            dropped++
            return false
        }
        return true
    }

    /** Runs after all accepted payloads, even if the queue is full. Duplicate finish is harmless. */
    @Synchronized fun finish(action: (Long) -> Unit) {
        if (finished) return
        finished = true
        accepting = false
        controls.submit { action(synchronized(this) { dropped }) }
        controls.release()
    }

    /** Stop after a storage failure and release this session's pending payloads. */
    @Synchronized fun fail() {
        accepting = false
        dropped++
        buffer.discard(this)
    }

    companion object {
        /** Refuse a new session if its open and close cannot both be guaranteed. */
        fun start(buffer: OrderedBuffer, onOverload: () -> Unit = {}, open: () -> Unit): DiagnosticCapture? {
            val controls = buffer.reserve(3) ?: return null
            val session = DiagnosticCapture(buffer, controls, onOverload)
            controls.submit(open)
            return session
        }
    }
}
