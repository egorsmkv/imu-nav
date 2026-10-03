package org.imunav.app.sensors

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.Executor

class ProviderStatusTest {
    @Test
    fun cachedReadsNeverQuerySystemAndExplicitCheckWaitsForWorker() = runBlocking {
        val queue = ArrayDeque<Runnable>()
        var enabled = false
        var queries = 0
        val status = ProviderStatus(Executor(queue::addLast)) {
            queries++
            enabled
        }
        repeat(20) { status.enabled.value }
        assertEquals(0, queries)
        status.refresh()
        queue.removeFirst().run()
        assertFalse(status.enabled.value)
        enabled = true
        val check = async(start = CoroutineStart.UNDISPATCHED) { status.check() }
        assertFalse(check.isCompleted)
        queue.removeFirst().run()
        assertEquals(true, check.await())
        assertEquals(true, status.enabled.value)
        assertEquals(2, queries)
    }
}
