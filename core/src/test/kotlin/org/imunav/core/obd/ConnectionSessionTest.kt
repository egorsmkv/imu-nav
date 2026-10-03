package org.imunav.core.obd

import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionSessionTest {
    private class Socket : Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun oldShutdownAndWatchdogCannotCloseTheReplacement() {
        val old = ConnectionSession<Socket>()
        val oldSocket = Socket()
        old.attach(oldSocket)
        val capturedByWatchdog = old.connection
        val capturedByStop = old.invalidate()
        val next = ConnectionSession<Socket>()
        val newSocket = Socket()
        next.attach(newSocket)
        capturedByStop?.close()
        capturedByWatchdog?.close()
        old.release(oldSocket)
        assertTrue(oldSocket.closed)
        assertFalse(newSocket.closed)
        assertTrue(next.active)
        assertFalse(old.active, "queued callbacks must see the old session as stopped")
    }

    @Test
    fun socketCreatedAfterStopIsClosedBeforeConnect() {
        val session = ConnectionSession<Socket>()
        session.invalidate()
        val lateSocket = Socket()
        assertFailsWith<InterruptedException> { session.attach(lateSocket) }
        assertTrue(lateSocket.closed)
    }

    @Test
    fun reconnectRetainsOwnershipWhenOldAttemptReleasesAgain() {
        val session = ConnectionSession<Socket>()
        val first = Socket()
        session.attach(first)
        session.release(first)
        val next = Socket()
        session.attach(next)
        session.release(first)
        assertSame(next, session.connection)
        assertFalse(next.closed)
    }
}
