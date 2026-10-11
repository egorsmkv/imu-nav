package org.imunav.app.cells

import org.imunav.core.diagnostics.DiagnosticOwner

/** Authentication boundary for account-owned consent; token/owner pairs must be read atomically. */
internal interface PrivacyAccount {
    var onPrivacyIdentityChanged: (() -> Unit)?
    fun diagnosticOwner(): DiagnosticOwner?
    fun diagnosticAccessToken(expected: DiagnosticOwner): String?
    fun ownerForToken(url: String, token: String): DiagnosticOwner?
    fun markSharingEnabled(expected: DiagnosticOwner)
}
