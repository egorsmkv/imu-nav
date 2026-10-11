package org.imunav.core.privacy

import org.imunav.core.diagnostics.DiagnosticOwner
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsentNoticeRequestTest {
    @Test
    fun delayedNoticeCannotConfirmAnotherServerOrAccount() {
        val owner = DiagnosticOwner("https://a.invalid", 1, "synthetic-A")
        val request = ConsentNoticeRequest(owner.server, owner)
        assertTrue(request.matches(" https://a.invalid/ ", owner))
        assertFalse(request.matches("https://b.invalid", owner))
        assertFalse(request.matches(owner.server, owner.copy(accountId = 2, syncIdentity = "synthetic-B")))
        assertFalse(request.matches(owner.server, owner.copy(syncIdentity = "recreated")))
        assertFalse(request.matches(owner.server, null))
    }

    @Test
    fun registrationNoticeBecomesStaleAfterLoginOrServerChange() {
        val request = ConsentNoticeRequest("https://a.invalid", null)
        assertTrue(request.matches("https://a.invalid", null))
        assertFalse(request.matches("https://b.invalid", null))
        assertFalse(request.matches(request.server, DiagnosticOwner(request.server, 1, "synthetic-A")))
    }
}
