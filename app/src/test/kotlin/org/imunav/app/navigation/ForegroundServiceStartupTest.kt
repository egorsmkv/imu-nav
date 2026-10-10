package org.imunav.app.navigation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceStartupTest {
    @Test
    fun schedulingServiceCannotPublishActiveNavigationBeforeForegroundConfirmation() = runBlocking {
        val startup = ForegroundServiceStartup()
        var active = false
        var requests = 0
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            startup.await { requests++ }
            active = true
        }
        assertEquals(1, requests)
        assertFalse(active)
        startup.succeeded()
        recovery.await()
        assertTrue(active)
        startup.await { error("already foreground") }
    }

    @Test
    fun androidRejectionLeavesNavigationInactiveAndAnotherRequestCanRetry() = runBlocking {
        supervisorScope {
            val startup = ForegroundServiceStartup()
            var active = false
            val recovery = async(start = CoroutineStart.UNDISPATCHED) {
                startup.await {}
                active = true
            }
            startup.failed(SecurityException("location revoked"))
            assertTrue(runCatching { recovery.await() }.exceptionOrNull() is SecurityException)
            assertFalse(active)
            assertFalse(startup.started)
            val retry = async(start = CoroutineStart.UNDISPATCHED) { startup.await { startup.succeeded() } }
            retry.await()
            assertTrue(startup.started)
        }
    }

    @Test
    fun synchronousDenialDoesNotLeaveAPendingWaiter() = runBlocking {
        val startup = ForegroundServiceStartup()
        assertTrue(runCatching { startup.await { throw IllegalStateException("background start denied") } }.isFailure)
        startup.await { startup.succeeded() }
        assertTrue(startup.started)
    }

    @Test
    fun cancellingOneWaiterDoesNotLoseTheOtherForegroundConfirmation() = runBlocking {
        val startup = ForegroundServiceStartup()
        var requests = 0
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { startup.await { requests++ } }
        val remaining = async(start = CoroutineStart.UNDISPATCHED) { startup.await { requests++ } }
        cancelled.cancel()
        cancelled.join()
        assertEquals(1, requests)
        assertFalse(remaining.isCompleted)
        startup.succeeded()
        remaining.await()
        assertTrue(startup.started)
    }
}
