package org.imunav.core.util

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackGenerationTest {
    @Test
    fun stoppedAndSupersededCallbacksCannotPublishOrPersist() {
        val sessions = CallbackGeneration()
        sessions.start()
        val old = assertNotNull(sessions.begin())
        assertTrue(old.current)
        sessions.stop()
        assertFalse(old.current)
        sessions.start()
        assertFalse(old.current)
        // Old cleanup still occupies the only work slot until its callbacks return.
        assertNull(sessions.begin())
        old.finish()
        val next = assertNotNull(sessions.begin())
        assertTrue(next.current)
        old.finish()
        assertNull(sessions.begin())
        next.finish()
        assertNotNull(sessions.begin())
    }

    @Test
    fun repeatedPollsCannotAccumulateOutstandingWork() {
        val sessions = CallbackGeneration()
        assertNull(sessions.begin())
        sessions.start()
        val work = assertNotNull(sessions.begin())
        repeat(1000) { assertNull(sessions.begin()) }
        work.finish()
        assertNotNull(sessions.begin())
    }
}
