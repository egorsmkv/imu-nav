package org.imunav.app.packs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PackTasksTest {
    @Test
    fun cancelledCleanupRetainsOwnershipUntilItFinishes() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tasks = PackTasks(scope)
        val events = mutableListOf<String>()
        var extraction: Continuation<Unit>? = null
        var cleanup: Continuation<Unit>? = null
        tasks.launch { operation ->
            try {
                suspendCoroutine<Unit> { extraction = it }
                operation.checkCancelled()
                events += "commit"
            } catch (_: InterruptedException) {
                events += "cancelled"
            } finally {
                withContext(NonCancellable) {
                    suspendCoroutine<Unit> { cleanup = it }
                    events += "cleaned"
                }
            }
        }
        dispatcher.drain()
        tasks.cancel()
        extraction?.resume(Unit)
        dispatcher.drain()
        assertTrue(tasks.running)
        assertFalse(tasks.launch { events += "overlap" })
        cleanup?.resume(Unit)
        dispatcher.drain()
        assertFalse(tasks.running)
        assertTrue(tasks.launch { events += "next" })
        dispatcher.drain()
        assertEquals(listOf("cancelled", "cleaned", "next"), events)
        scope.cancel()
    }

    @Test
    fun cancelledJobRemainsBusyWhileNonCancellableCleanupIsSuspended() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tasks = PackTasks(scope)
        var cleanup: Continuation<Unit>? = null
        tasks.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { suspendCoroutine<Unit> { cleanup = it } }
            }
        }
        dispatcher.drain()
        val job = requireNotNull(scope.coroutineContext[Job]).children.single()
        job.cancel()
        dispatcher.drain()
        assertFalse(job.isActive)
        assertTrue(tasks.running)
        assertFalse(tasks.launch { error("cleanup still owns staging") })
        cleanup?.resume(Unit)
        dispatcher.drain()
        assertFalse(tasks.running)
        scope.cancel()
    }

    @Test
    fun ownershipIsReservedBeforeImmediateExecution() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = PackTasks(scope)
        tasks.launch {
            assertTrue(tasks.running)
            assertFalse(tasks.launch { error("must not overlap") })
        }
        assertFalse(tasks.running)
        scope.cancel()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
