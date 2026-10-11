package org.imunav.app.cells

import android.content.SharedPreferences
import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.diagnostics.DiagnosticOwnerChanged
import org.imunav.core.net.LoopbackServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CellPrivacyOwnershipTest {
    private class Account(@Volatile var current: DiagnosticOwner?) : PrivacyAccount {
        @Volatile override var onPrivacyIdentityChanged: (() -> Unit)? = null
        override fun diagnosticOwner() = current
        override fun diagnosticAccessToken(expected: DiagnosticOwner): String? = if (current == expected) "synthetic-${expected.accountId}" else null
        override fun ownerForToken(url: String, token: String) = current?.takeIf { it.server == url && token == "synthetic-${it.accountId}" }
        override fun markSharingEnabled(expected: DiagnosticOwner) = Unit
        fun switchTo(owner: DiagnosticOwner) {
            onPrivacyIdentityChanged?.invoke()
            current = owner
        }
    }

    @Test
    fun originalWithdrawalWaitsForOriginalAccountAndCannotDeleteSecondAccountsData() {
        val requests = CopyOnWriteArrayList<String>()
        LoopbackServer({ socket ->
            requests += LoopbackServer.readRequest(socket).line
            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
        }, {}).use { server ->
            val owner = DiagnosticOwner("http://127.0.0.1:${server.port}", 1, "synthetic-A")
            val account = Account(owner)
            val prefs = Preferences()
            // Local server compatibility still needs an explicit receipt for this account.
            prefs.values["local:${owner.server}"] = true
            val privacy = CellPrivacy(prefs.instance, account)
            privacy.grant(owner, "diagnostics", CellPrivacy.LOCAL_VERSION)
            privacy.withdrawLocally(owner.server, "diagnostics")
            account.switchTo(owner.copy(accountId = 2, syncIdentity = "synthetic-B"))
            privacy.flushWithdrawals(owner.server)
            assertTrue(requests.isEmpty())
            assertFalse(privacy.canCaptureDiagnostics(owner.server))
            account.switchTo(owner)
            privacy.flushWithdrawals(owner.server)
            assertEquals(listOf("DELETE /v1/privacy/consents/diagnostics HTTP/1.1"), requests.toList())
            privacy.flushWithdrawals(owner.server)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun switchingDuringWithdrawalLeavesOriginalIntentForRetry() {
        val count = AtomicInteger()
        val account = AtomicReference<Account?>()
        val first = AtomicReference<DiagnosticOwner?>()
        LoopbackServer({ socket ->
            LoopbackServer.readRequest(socket)
            if (count.incrementAndGet() == 1) {
                first.get()?.let { account.get()?.switchTo(it.copy(accountId = 2, syncIdentity = "synthetic-B")) }
            }
            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
        }, {}).use { server ->
            val owner = DiagnosticOwner("http://127.0.0.1:${server.port}", 1, "synthetic-A")
            val session = Account(owner)
            account.set(session)
            first.set(owner)
            val privacy = CellPrivacy(Preferences().instance, session)
            privacy.withdrawLocally(owner.server, "diagnostics")
            var rejected = false
            try {
                privacy.flushWithdrawals(owner.server)
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue(rejected)
            session.switchTo(owner)
            privacy.flushWithdrawals(owner.server)
            assertEquals(2, count.get())
            privacy.flushWithdrawals(owner.server)
            assertEquals(2, count.get())
        }
    }

    @Test
    fun grantsNeverCrossAccountsAndLegacyReceiptsCannotEnableCapture() {
        val owner = DiagnosticOwner("https://example.invalid", 1, "synthetic-A")
        val account = Account(owner)
        val prefs = Preferences()
        prefs.values["local:${owner.server}"] = true
        prefs.values["version:${owner.server}"] = "legacy"
        prefs.values["grant:${owner.server}:diagnostics"] = true
        val privacy = CellPrivacy(prefs.instance, account)
        assertFalse(privacy.canCaptureDiagnostics(owner.server))
        privacy.grant(owner, "diagnostics", CellPrivacy.LOCAL_VERSION)
        assertTrue(privacy.canCaptureDiagnostics(owner.server))
        account.switchTo(owner.copy(accountId = 2, syncIdentity = "synthetic-B"))
        assertFalse(privacy.canCaptureDiagnostics(owner.server))
        var rejected = false
        try {
            privacy.grant(owner, "diagnostics", CellPrivacy.LOCAL_VERSION)
        } catch (_: DiagnosticOwnerChanged) {
            rejected = true
        }
        assertTrue(rejected)
        account.switchTo(owner)
        assertTrue(privacy.canCaptureDiagnostics(owner.server))
        account.switchTo(owner.copy(syncIdentity = "recreated"))
        assertFalse(privacy.canCaptureDiagnostics(owner.server))
    }

    /** Android storage is replaced by a small in-memory adapter; production privacy logic and HTTP execute unchanged. */
    private class Preferences {
        val values = mutableMapOf<String, Any>()
        private val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { receiver, method, arguments ->
            when {
                method.name.startsWith("put") -> {
                    val key = arguments[0] as String
                    arguments[1]?.let { values[key] = it } ?: values.remove(key)
                    receiver
                }

                method.name == "remove" -> {
                    values.remove(arguments[0])
                    receiver
                }

                method.name == "commit" -> true

                else -> null
            }
        }
        val instance = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
            when {
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(arguments[0])
                method.name.startsWith("get") -> values[arguments[0]] ?: arguments[1]
                else -> null
            }
        } as SharedPreferences
    }
}
