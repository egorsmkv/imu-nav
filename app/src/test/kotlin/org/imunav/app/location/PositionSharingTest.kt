package org.imunav.app.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.geo.GeoPoint
import org.imunav.core.location.PositionSharingAccount
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Host tests exercise the production output workers without Android services or an account's real data. */
class PositionSharingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(1_000)
    private val addresses = AtomicReference(listOf("127.0.0.1"))
    private val addressThread = AtomicReference<Thread?>()
    private val account = object : PositionSharingAccount {
        override fun owner(): DiagnosticOwner? = null
        override fun token(expected: DiagnosticOwner): String? = null
        override fun onIdentityChanged(callback: () -> Unit) = Unit
    }
    private val sharing = PositionSharing(scope, account, clock::get) {
        addressThread.set(Thread.currentThread())
        addresses.get()
    }
    private val guidance = GuidanceState(active = true, position = GeoPoint(48.125, 28.25), source = PositionSource.DR, uncertaintyM = 75.0)

    @After
    fun close() {
        sharing.stop()
        runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
    }

    @Test
    fun bothModeKeepsLocalWorkingWithoutInternetAccountAndIdleStopRequiresNewOptIn() = runBlocking {
        sharing.start(PositionSharingMode.LOCAL, "127.0.0.1", navigating = false)
        assertFalse(sharing.status.value.enabled)
        sharing.refreshAddresses()
        await { it.addresses.isNotEmpty() }
        assertTrue(addressThread.get() != Thread.currentThread())
        sharing.start(PositionSharingMode.BOTH, "127.0.0.1", navigating = true)
        sharing.update(guidance, clock.get(), 1_767_225_600_000, 500)
        await { it.local.phase == PositionSharingPhase.ACTIVE && it.internet.phase == PositionSharingPhase.ACCOUNT_REQUIRED }
        Socket("127.0.0.1", 10110).use { socket ->
            socket.soTimeout = 3_000
            val reader = socket.getInputStream().bufferedReader()
            assertTrue(reader.readLine().contains(",,,E*"))
            await { it.local.receivers == 1 }
            sharing.update(guidance.copy(active = false), 1_500, 1_767_225_600_500, 500)
            assertNull(reader.readLine())
        }
        assertFalse(sharing.status.value.enabled)
        clock.set(2_000)
        sharing.update(guidance, 2_000, 1_767_225_601_000, 500)
        assertEquals(PositionSharingPhase.OFF, sharing.status.value.local.phase)
        // Navigation output alone must never restart a stream after a trip ends.
        assertFalse(sharing.status.value.enabled)
    }

    @Test
    fun invalidPositionDisconnectsThenOnlyTheLatestFreshPositionIsSent() = runBlocking {
        sharing.start(PositionSharingMode.LOCAL, "127.0.0.1", navigating = true)
        sharing.update(guidance, clock.get(), 1_767_225_600_000, 1_000)
        await { it.local.phase == PositionSharingPhase.ACTIVE }
        Socket("127.0.0.1", 10110).use { socket ->
            socket.soTimeout = 3_000
            val reader = socket.getInputStream().bufferedReader()
            assertTrue(reader.readLine().startsWith("\$GPRMC,"))
            clock.set(1_500)
            sharing.update(guidance.copy(position = null), 1_500, 1_767_225_600_500, 1_000)
            await { it.local.phase == PositionSharingPhase.WAITING }
            assertNull(reader.readLine())
        }
        clock.set(3_000)
        sharing.update(guidance.copy(position = GeoPoint(51.0, 31.0)), 3_000, 1_767_225_602_000, 1_000)
        await { it.local.phase == PositionSharingPhase.ACTIVE }
        Socket("127.0.0.1", 10110).use { socket ->
            socket.soTimeout = 3_000
            assertTrue(socket.getInputStream().bufferedReader().readLine().contains(",5100.000000,N,03100.000000,E,"))
        }
    }

    @Test
    fun losingTheSelectedAddressStopsLocalSharingWithoutSelectingAnotherInterface() = runBlocking {
        sharing.start(PositionSharingMode.LOCAL, "127.0.0.1", navigating = true)
        sharing.update(guidance, clock.get(), 1_767_225_600_000, 500)
        await { it.local.phase == PositionSharingPhase.ACTIVE }
        addresses.set(listOf("127.0.0.2"))
        clock.set(1_500)
        sharing.update(guidance, 1_500, 1_767_225_600_500, 500)
        await { it.local.phase == PositionSharingPhase.NETWORK_ERROR }
        assertNull(sharing.status.value.local.endpoint)
        assertEquals(0, sharing.status.value.local.receivers)
        sharing.stop()
        assertFalse(sharing.status.value.enabled)
    }

    private suspend fun await(predicate: (PositionSharingStatus) -> Boolean) = withTimeout(5_000) { sharing.status.first(predicate) }
}
