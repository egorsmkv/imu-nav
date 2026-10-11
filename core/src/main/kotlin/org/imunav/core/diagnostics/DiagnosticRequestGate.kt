package org.imunav.core.diagnostics

import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** An account transition must preserve unsent local data rather than acknowledge it under new credentials. */
class DiagnosticOwnerChanged : IOException("Diagnostic account changed")

/** Owner checks surround every request; authentication callbacks cancel calls without taking account locks. */
class DiagnosticRequestGate(private val currentOwner: () -> DiagnosticOwner?, private val allowed: () -> Boolean) {
    private val calls = mutableSetOf<Call>()

    /** No account reads here: authentication invokes this while holding its own session lock. */
    fun cancelAll() = synchronized(calls) { calls.forEach(Call::cancel) }

    /** Cancellation and a post-response check prevent an old request from acknowledging a new account's spool. */
    fun <T> execute(owner: DiagnosticOwner, call: Call, read: (Response) -> T): T {
        checkOwner(owner)
        synchronized(calls) { calls.add(call) }
        try {
            // Close the race where an account changed between the first check and call registration.
            checkOwner(owner)
            val result = call.execute().use(read)
            checkOwner(owner)
            return result
        } finally {
            synchronized(calls) { calls.remove(call) }
        }
    }

    private fun checkOwner(owner: DiagnosticOwner) {
        if (!allowed() || currentOwner() != owner) throw DiagnosticOwnerChanged()
    }
}
