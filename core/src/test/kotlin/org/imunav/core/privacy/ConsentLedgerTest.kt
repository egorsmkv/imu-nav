package org.imunav.core.privacy

import org.imunav.core.diagnostics.DiagnosticOwner
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentLedgerTest {
    private class MemoryStorage : ConsentStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String?>) {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
        }
    }
    private val storage = MemoryStorage()
    private val ledger = ConsentLedger(storage)
    private val first = DiagnosticOwner("https://example.invalid", 1, "synthetic-A")
    private val second = first.copy(accountId = 2, syncIdentity = "synthetic-B")

    @Test
    fun livePositionConsentIsIndependentAndCannotMoveToAnotherAccount() {
        assertTrue(ledger.grant(first, "diagnostics", "v1"))
        assertFalse(ledger.granted(first, "live_position", "v1"))
        assertTrue(ledger.grant(first, "live_position", "v1"))
        assertFalse(ledger.granted(second, "live_position", "v1"))
        ledger.withdraw(first, "live_position")
        assertFalse(ledger.granted(first, "live_position", "v1"))
        assertTrue(ledger.granted(first, "diagnostics", "v1"))
    }

    @Test
    fun accountSwitchAndRestartPreserveOriginalWithdrawalWithoutInheritingConsent() {
        assertTrue(ledger.grant(first, "diagnostics", "v1"))
        assertFalse(ledger.granted(second, "diagnostics", "v1"))
        ledger.withdraw(first, "diagnostics")
        val restored = ConsentLedger(storage)
        assertNull(restored.pending(second, "diagnostics"))
        val original = assertNotNull(restored.pending(first, "diagnostics"))
        restored.acknowledge(second, "diagnostics", original)
        assertNotNull(restored.pending(first, "diagnostics"))
        restored.acknowledge(first, "diagnostics", original)
        assertNull(restored.pending(first, "diagnostics"))
    }

    @Test
    fun recreatedAccountAndDifferentServerCannotInheritReceipt() {
        assertTrue(ledger.grant(first, "tower_upload", "v1"))
        assertFalse(ledger.granted(first.copy(syncIdentity = "recreated"), "tower_upload", "v1"))
        assertFalse(ledger.granted(first.copy(server = "https://another.invalid"), "tower_upload", "v1"))
    }

    @Test
    fun lateResponseCannotClearNewerWithdrawalOrRestoreWithdrawnConsent() {
        ledger.withdraw(first, "diagnostics")
        val old = assertNotNull(ledger.pending(first, "diagnostics"))
        ledger.withdraw(first, "diagnostics")
        ledger.acknowledge(first, "diagnostics", old)
        assertNotNull(ledger.pending(first, "diagnostics"))
        assertFalse(ledger.grant(first, "diagnostics", "v1"))
        assertFalse(ledger.granted(first, "diagnostics", "v1"))
    }

    @Test
    fun staleRemoteReconciliationCannotEraseNewConfirmationOfSameVersion() {
        assertTrue(ledger.grant(first, "diagnostics", "v1"))
        val beforeRequest = ledger.stamp(first, "diagnostics")
        assertTrue(ledger.grant(first, "diagnostics", "v1"))
        ledger.revokeIfUnchanged(first, "diagnostics", beforeRequest)
        assertTrue(ledger.granted(first, "diagnostics", "v1"))
        ledger.revokeIfUnchanged(first, "diagnostics", ledger.stamp(first, "diagnostics"))
        assertFalse(ledger.granted(first, "diagnostics", "v1"))
    }

    @Test
    fun legacyUrlOnlyRecordsAreQuarantinedAndRemoteRevocationStaysAccountScoped() {
        storage.values["grant:${first.server}:diagnostics"] = "true"
        storage.values["pending:${first.server}:diagnostics"] = "true"
        assertFalse(ledger.granted(first, "diagnostics", "v1"))
        assertNull(ledger.pending(first, "diagnostics"))
        assertTrue(ledger.grant(first, "diagnostics", "v1"))
        assertTrue(ledger.grant(second, "diagnostics", "v1"))
        ledger.revoke(first, "diagnostics")
        assertFalse(ledger.granted(first, "diagnostics", "v1"))
        assertTrue(ledger.granted(second, "diagnostics", "v1"))
    }
}
