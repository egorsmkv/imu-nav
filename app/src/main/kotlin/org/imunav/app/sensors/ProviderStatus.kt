package org.imunav.app.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Cached system state: reads never call binder; explicit checks and notifications use the worker. */
class ProviderStatus(private val worker: Executor, private val query: () -> Boolean) {
    private val _enabled = MutableStateFlow(true)
    val enabled = _enabled.asStateFlow()

    fun refresh() = worker.execute { read() }

    /** Navigation startup waits for a fresh check instead of trusting an initial or stale cache. */
    suspend fun check(): Boolean = suspendCancellableCoroutine { continuation ->
        worker.execute {
            if (continuation.isActive) continuation.resume(read())
        }
    }

    private fun read(): Boolean = runCatching(query).getOrDefault(false).also { _enabled.value = it }
}
