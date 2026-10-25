package org.imunav.app.location

import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.location.PositionSharingAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The relay refuses to send credentials or positions before any connection could leak them. */
class LivePositionRelayTest {
    private fun account(owner: DiagnosticOwner, token: String?) = object : PositionSharingAccount {
        override fun owner() = owner

        override fun token(expected: DiagnosticOwner) = token

        override fun onIdentityChanged(callback: () -> Unit) = Unit
    }

    @Test
    fun plainHttpServerIsRefusedBeforeAnyRequest() {
        val owner = DiagnosticOwner("http://127.0.0.1:9", 7, "identity-1")
        val relay = LivePositionRelay(account(owner, "token"), owner) { true }
        val failure = runCatching { relay.start() }.exceptionOrNull()
        assertEquals("Live position requires HTTPS", failure?.message)
        assertNull(relay.endpoint)
        relay.stop()
        relay.cancel()
    }

    @Test
    fun missingTokenIsAPermissionFailureWithoutServerDetails() {
        val owner = DiagnosticOwner("https://127.0.0.1:9", 7, "identity-1")
        val relay = LivePositionRelay(account(owner, null), owner) { true }
        val failure = runCatching { relay.start() }.exceptionOrNull()
        assertEquals(403, (failure as RelayFailure).code)
        assertEquals("Live relay request failed", failure.message)
        assertEquals(0, relay.receivers)
        check(failure is IOException)
    }
}
