package org.imunav.app.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread owner for asynchronous navigation changes; only its latest request may commit. */
class NavigationWork(private val scope: CoroutineScope) {
    private var generation = 0L
    private var job: Job? = null

    /** Invalidate first: even a router that ignores cancellation cannot publish an old result. */
    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    /** Failures from superseded requests never change the current trip or its error message. */
    // The async task boundary reports routing, JNI and Android failures without killing the main scope.
    @Suppress("TooGenericExceptionCaught")
    fun launch(onFailure: (Exception) -> Unit, onFinished: () -> Unit = {}, block: suspend Request.() -> Unit) {
        cancel()
        val request = Request(generation)
        job = scope.launch {
            try {
                request.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request.isCurrent()) onFailure(failure)
            } finally {
                // A current request can cancel itself (for example a router timeout).
                if (request.isCurrent()) onFinished()
            }
        }
    }

    /** The token is checked after every preparation boundary and before transferring ownership. */
    inner class Request internal constructor(private val token: Long) {
        fun isCurrent(): Boolean = token == generation
        fun ensureCurrent() {
            if (!isCurrent()) throw CancellationException("navigation request superseded")
        }
    }
}

/** Owns a prepared resource until the receiving component explicitly takes ownership. */
class PreparedResource<T : AutoCloseable>(val value: T) : AutoCloseable {
    private var owned = true
    fun transfer() {
        owned = false
    }
    override fun close() {
        if (owned) {
            owned = false
            value.close()
        }
    }
}

/** Capture ownership inside the worker so cancellation on dispatch back cannot leak its result. */
suspend fun <T : AutoCloseable> withPreparedResource(create: () -> T, dispatcher: CoroutineDispatcher = Dispatchers.Default, consume: (PreparedResource<T>) -> Unit) {
    var prepared: PreparedResource<T>? = null
    try {
        withContext(dispatcher) { prepared = PreparedResource(create()) }
        consume(checkNotNull(prepared))
    } finally {
        prepared?.close()
    }
}
