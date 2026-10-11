package org.imunav.core.diagnostics

import org.imunav.core.util.OrderedBuffer
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticCaptureTest {
    private val tasks = ArrayDeque<Runnable>()
    private val worker = Executor { tasks.addLast(it) }

    @Test
    fun stalledDiskBoundsPayloadsAndPreservesOverloadAndFinish() {
        val queue = OrderedBuffer(worker, 2, 10, 3)
        val order = mutableListOf<String>()
        val session = assertNotNull(DiagnosticCapture.start(queue, { order += "incomplete" }) { order += "open" })
        assertNull(DiagnosticCapture.start(queue) {})
        assertTrue(session.offer(5) { order += "one" })
        assertTrue(session.offer(5) { order += "two" })
        repeat(1000) { assertFalse(session.offer(1) {}) }
        assertEquals(10, queue.stats.bytes)
        assertEquals(1, tasks.size)
        session.finish { omitted -> order += "finish:$omitted" }
        session.finish { error("duplicate finish") }
        tasks.removeFirst().run()
        assertEquals(listOf("open", "one", "two", "incomplete", "finish:1000"), order)
        assertEquals(0, queue.stats.bytes)
        assertNotNull(DiagnosticCapture.start(queue) {}).finish {}
        tasks.removeFirst().run()
    }

    @Test
    fun rapidRestartsPreserveSessionOwnershipAndFreeUnusedControl() {
        val queue = OrderedBuffer(worker, 3, 30, 6)
        val order = mutableListOf<String>()
        val old = assertNotNull(DiagnosticCapture.start(queue) { order += "open A" })
        val next = assertNotNull(DiagnosticCapture.start(queue) { order += "open B" })
        assertTrue(old.offer(10) { order += "A event" })
        assertTrue(next.offer(10) { order += "B event" })
        old.finish { order += "close A" }
        next.finish { order += "close B" }
        tasks.removeFirst().run()
        assertEquals(listOf("open A", "open B", "A event", "B event", "close A", "close B"), order)
        assertNotNull(queue.reserve(6)).release()
    }

    @Test
    fun failureDiscardsOnlyItsOwnWaitingPayloads() {
        val queue = OrderedBuffer(worker, 2, 20)
        val first = assertNotNull(DiagnosticCapture.start(queue) {})
        val second = assertNotNull(DiagnosticCapture.start(queue) {})
        assertTrue(first.offer(10) { error("discarded") })
        assertTrue(second.offer(10) {})
        first.fail()
        assertEquals(10, queue.stats.bytes)
        first.finish { assertTrue(it > 0) }
        second.finish { assertEquals(0, it) }
        tasks.removeFirst().run()
    }
}
