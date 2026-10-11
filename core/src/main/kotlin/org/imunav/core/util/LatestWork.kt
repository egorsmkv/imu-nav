package org.imunav.core.util

import java.util.concurrent.Executor

/** Keeps only the latest pending snapshot when storage is stalled, without blocking its producer. */
class LatestWork(private val worker: Executor) {
    private var pending: (() -> Unit)? = null
    private var scheduled = false

    @Synchronized fun submit(action: () -> Unit) {
        pending = action
        if (!scheduled) {
            scheduled = true
            worker.execute(::drain)
        }
    }

    private fun drain() {
        var completedNormally = false
        try {
            while (true) {
                val action = synchronized(this) {
                    val next = pending
                    pending = null
                    if (next == null) {
                        scheduled = false
                        completedNormally = true
                    }
                    next
                } ?: return
                action()
            }
        } finally {
            if (!completedNormally) {
                synchronized(this) {
                    scheduled = false
                    if (pending != null) {
                        scheduled = true
                        worker.execute(::drain)
                    }
                }
            }
        }
    }
}
