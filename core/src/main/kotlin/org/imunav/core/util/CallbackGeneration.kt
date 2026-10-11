package org.imunav.core.util

/** Invalidate callbacks synchronously while allowing one outstanding batch to finish its cleanup after stop/restart. */
class CallbackGeneration {
    private var owner: Any? = null
    private var pending: Work? = null

    @Synchronized fun start() {
        owner = Any()
    }

    @Synchronized fun stop() {
        owner = null
    }

    /** Coalesce polls until every callback in the batch has returned, even across restarts. */
    @Synchronized fun begin(): Work? {
        val current = owner ?: return null
        if (pending != null) return null
        return Work(current).also { pending = it }
    }

    /** Capture once at admission and recheck at conversion, persistence admission and main-thread delivery. */
    inner class Work internal constructor(val owner: Any) {
        val current: Boolean get() = synchronized(this@CallbackGeneration) { this.owner === this@CallbackGeneration.owner }

        fun finish() = synchronized(this@CallbackGeneration) {
            if (pending === this) pending = null
        }
    }
}
