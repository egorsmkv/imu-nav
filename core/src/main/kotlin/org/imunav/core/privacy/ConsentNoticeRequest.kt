package org.imunav.core.privacy

import org.imunav.core.diagnostics.DiagnosticOwner

/** Consent can only confirm the server and account for which the operator notice was fetched. */
data class ConsentNoticeRequest(val server: String, val owner: DiagnosticOwner?) {
    fun matches(server: String, owner: DiagnosticOwner?): Boolean = this.server == server.trim().trimEnd('/') && this.owner == owner
}
