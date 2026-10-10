package org.imunav.app.navigation

import kotlinx.coroutines.CompletableDeferred

/** Main-thread handshake: scheduling a service is not evidence that Android promoted it. */
internal class ForegroundServiceStartup {
    private var ready = false
    private var pending: CompletableDeferred<Unit>? = null
    private var waiters = 0

    val started: Boolean get() = ready

    /** Coalesce requests and wait for the real startForeground result before installing navigation. */
    suspend fun await(start: () -> Unit) {
        if (ready) return
        val existing = pending
        val result = existing ?: CompletableDeferred<Unit>().also { pending = it }
        waiters++
        try {
            if (existing == null) start()
            result.await()
        } finally {
            waiters--
            if (waiters == 0 && pending === result) pending = null
        }
    }

    fun succeeded() {
        ready = true
        pending?.complete(Unit)
    }

    fun failed(cause: Exception) {
        ready = false
        pending?.completeExceptionally(cause)
    }
}
