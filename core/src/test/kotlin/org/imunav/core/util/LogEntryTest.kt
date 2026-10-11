package org.imunav.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic diagnostic lines exercise presentation without modifying exported evidence. */
class LogEntryTest {
    @Test
    fun separatesClockAndMonotonicTimeAndKeepsMessageIntact() {
        val raw = "12:34:56.789 [12345] gps_state=LOST details=[test] value=Київ\nnext line"
        val entry = LogEntry.parse(raw)
        assertEquals(raw, entry.raw)
        assertEquals("12:34:56.789", entry.timestamp)
        assertEquals(12345L, entry.elapsedMs)
        assertEquals("gps_state=LOST details=[test] value=Київ\nnext line", entry.message)
        assertTrue(entry.isProblem)
        assertTrue(entry.isPosition)
        assertFalse(entry.isNavigation)
    }

    @Test
    fun bracketsAndMalformedPrefixesRemainVisible() {
        listOf("", "details=[test] message", "12:34:56.789 [invalid] route_failed", "[42] gps_state=GOOD").forEach { raw ->
            val entry = LogEntry.parse(raw)
            assertEquals(raw, entry.raw)
            assertEquals(raw, entry.message)
            assertEquals("", entry.timestamp)
            assertNull(entry.elapsedMs)
        }
    }

    @Test
    fun overflowingElapsedTimeDoesNotDiscardMessage() {
        val entry = LogEntry.parse("12:34:56.789 [999999999999999999999999] route_failed reason=test")
        assertNull(entry.elapsedMs)
        assertEquals("route_failed reason=test", entry.message)
        assertTrue(entry.isNavigation)
        assertTrue(entry.isProblem)
    }
}
