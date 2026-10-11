package org.imunav.core.location

import org.imunav.core.diagnostics.DiagnosticOwner

/** Account boundary for output-only sharing; token refresh is blocking and must run on a worker. */
interface PositionSharingAccount {
    fun owner(): DiagnosticOwner?
    fun token(expected: DiagnosticOwner): String?

    /** Authentication invokes this callback under its lock; callbacks may only cancel requests. */
    fun onIdentityChanged(callback: () -> Unit)
}
