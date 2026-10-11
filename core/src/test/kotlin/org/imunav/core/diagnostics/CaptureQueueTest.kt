package org.imunav.core.diagnostics

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureQueueTest {
    @Test
    fun writerFailureReleasesCurrentPayloadThenClosesAndDiscardsWaitingInput() {
        val queue = CaptureQueue<String>(4, 100)
        assertTrue(queue.offer("current", 30))
        assertTrue(queue.offer("waiting", 40))
        assertFailsWith<IOException> { queue.consume(0) { throw IOException("synthetic disk failure") } }
        assertEquals(40, queue.stats.bytes)
        assertEquals(1, queue.close(discard = true))
        repeat(1000) { assertFalse(queue.offer("late", 1)) }
        assertEquals(0, queue.stats.items)
        assertEquals(0, queue.stats.bytes)
        assertFalse(queue.consume(0) { error("discarded payload") })
    }

    @Test
    fun payloadRemainsChargedUntilWriteCompletesAndNormalStopDrainsPrefix() {
        val queue = CaptureQueue<String>(2, 10)
        assertTrue(queue.offer("first", 5))
        assertTrue(queue.offer("second", 5))
        assertTrue(
            queue.consume(0) {
                assertFalse(queue.offer("overflow", 1))
                assertEquals(10, queue.stats.bytes)
            },
        )
        queue.close()
        assertFalse(queue.offer("after stop", 1))
        assertTrue(queue.consume(0) { assertEquals("second", it) })
        assertEquals(0, queue.stats.bytes)
    }

    @Test
    fun aSingleOversizedPayloadIsRejectedWithoutSerialization() {
        val queue = CaptureQueue<String>(100, 10)
        assertFalse(queue.offer("oversized", 11))
        assertEquals(0, queue.stats.items)
        assertTrue(queue.offer("small", 10))
    }
}
