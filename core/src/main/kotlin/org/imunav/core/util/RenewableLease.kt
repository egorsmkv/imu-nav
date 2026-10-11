package org.imunav.core.util

/** Renew a timed resource lease before expiry, using monotonic time rather than wall-clock changes. */
class RenewableLease(private val timeoutMs: Long, private val renewEveryMs: Long) {
    private var nextRenewalMs = Long.MIN_VALUE

    init {
        require(renewEveryMs > 0 && timeoutMs > renewEveryMs)
    }

    /** Call from the owning loop; resource operations may be dispatched to its serial control worker. */
    fun renewIfDue(nowMs: Long, acquire: (Long) -> Unit) {
        if (nowMs < nextRenewalMs) return
        acquire(timeoutMs)
        nextRenewalMs = nowMs + renewEveryMs
    }
}
