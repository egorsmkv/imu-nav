package org.imunav.core.util

import org.junit.Test
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderedBufferTest {
    private val pending = ArrayDeque<Runnable>()
    private val worker = Executor { pending.addLast(it) }

    @Test
    fun stalledWorkerBoundsBothBudgetsAndSchedulesOneDrain() {
        val buffer = OrderedBuffer(worker, 2, 10)
        val owner = Any()
        val completed = mutableListOf<Int>()
        assertTrue(buffer.offer(owner, 4) { completed += 1 })
        assertTrue(buffer.offer(owner, 6) { completed += 2 })
        repeat(1000) { assertFalse(buffer.offer(owner, 1) {}) }
        assertEquals(BufferStats(2, 10, 2, 10, 1000), buffer.stats)
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        assertEquals(listOf(1, 2), completed)
        assertEquals(0, buffer.stats.items)
        assertEquals(0, buffer.stats.bytes)
        assertFalse(buffer.offer(owner, 11) {})
        assertTrue(buffer.offer(owner, 10) {})
        pending.removeFirst().run()
    }

    @Test
    fun inFlightPayloadStaysChargedAndFinishReservationSurvivesOverload() {
        val buffer = OrderedBuffer(worker, 1, 10, 2)
        val lifecycle = assertNotNull(buffer.reserve(2))
        val order = mutableListOf<String>()
        lifecycle.submit { order += "open" }
        assertNull(buffer.reserve(1))
        assertTrue(
            buffer.offer(this, 10) {
                assertEquals(10, buffer.stats.bytes)
                assertFalse(buffer.offer(this, 1) {})
                order += "payload"
            },
        )
        lifecycle.submit { order += "close" }
        pending.removeFirst().run()
        assertEquals(listOf("open", "payload", "close"), order)
        assertNotNull(buffer.reserve(2)).release()
        assertEquals(0, buffer.stats.bytes)
    }

    @Test
    fun discardReleasesOnlyTheFailedOwnersWaitingPayloads() {
        val buffer = OrderedBuffer(worker, 2, 20)
        val failed = Any()
        val next = Any()
        assertTrue(buffer.offer(failed, 10) { error("discarded payload executed") })
        assertTrue(buffer.offer(next, 10) {})
        buffer.discard(failed)
        assertEquals(1, buffer.stats.items)
        assertEquals(10, buffer.stats.bytes)
        pending.removeFirst().run()
        assertEquals(0, buffer.stats.items)
    }

    @Test
    fun throwingCommandReleasesItsChargeAndReschedulesTheRemainingClose() {
        val buffer = OrderedBuffer(worker, 1, 10)
        val close = assertNotNull(buffer.reserve(1))
        var closed = false
        assertTrue(buffer.offer(this, 10) { error("synthetic command failure") })
        close.submit { closed = true }
        assertFailsWith<IllegalStateException> { pending.removeFirst().run() }
        assertEquals(0, buffer.stats.items)
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        assertTrue(closed)
        assertNotNull(buffer.reserve(64)).release()
    }
}
