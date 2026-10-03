package org.imunav.app.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class NavigationWorkTest {
    @Test
    fun lateRouterResultCannotCommitToNewTripOrPublishAnError() {
        val main = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val work = NavigationWork(scope)
        var result: Continuation<Unit>? = null
        val events = mutableListOf<String>()
        work.launch({ events.add("old failure") }, onFinished = { events.add("old finished") }) {
            suspendCoroutine<Unit> { result = it }
            ensureCurrent()
            events.add("old route")
        }
        main.drain()
        work.cancel()
        work.launch({ throw AssertionError(it) }) { events.add("new trip") }
        main.drain()
        result?.resume(Unit)
        main.drain()
        assertEquals(listOf("new trip"), events)
        scope.cancel()
    }

    @Test
    fun currentRouterCancellationClearsStartingStateWithoutReportingFailure() {
        val main = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        var starting = true
        NavigationWork(scope).launch({ throw AssertionError(it) }, onFinished = { starting = false }) {
            throw CancellationException("router cancelled")
        }
        main.drain()
        assertEquals(false, starting)
        scope.cancel()
    }

    @Test
    fun failureFromSupersededRouterCannotChangeNewTripError() {
        val main = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val work = NavigationWork(scope)
        var result: Continuation<Unit>? = null
        var failures = 0
        work.launch({ failures++ }) { suspendCoroutine<Unit> { result = it } }
        main.drain()
        work.launch({ throw AssertionError(it) }) {}
        main.drain()
        result?.resumeWithException(IllegalStateException("late router failure"))
        main.drain()
        assertEquals(0, failures)
        scope.cancel()
    }

    @Test
    fun installedResourceBelongsToReceiverAndIsNotClosedByPreparation() {
        val main = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val resource = CountedResource()
        NavigationWork(scope).launch({ throw AssertionError(it) }) {
            withPreparedResource(create = { resource }, dispatcher = main) { it.transfer() }
        }
        main.drain()
        assertEquals(0, resource.closes)
        resource.close()
        assertEquals(1, resource.closes)
        scope.cancel()
    }

    @Test
    fun cancellationWhileWorkerCreatesResourceClosesItOnReturn() {
        val main = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val work = NavigationWork(scope)
        val resource = CountedResource()
        work.launch({ throw AssertionError(it) }) {
            withPreparedResource(create = {
                work.cancel()
                resource
            }, dispatcher = worker) {
                error("cancelled result must never commit")
            }
        }
        main.drain()
        worker.drain()
        main.drain()
        assertEquals(1, resource.closes)
        scope.cancel()
    }

    @Test
    fun failedCommitClosesUntransferredResourceAndReportsFailure() {
        val main = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val work = NavigationWork(scope)
        val resource = CountedResource()
        var failures = 0
        work.launch({ failures++ }) {
            withPreparedResource(create = { resource }, dispatcher = main) { error("commit failed") }
        }
        main.drain()
        assertEquals(1, resource.closes)
        assertEquals(1, failures)
        scope.cancel()
    }

    /** Keeps both dispatch boundaries under test control without delays or Android loopers. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class CountedResource : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
        }
    }
}
