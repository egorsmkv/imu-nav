package org.imunav.core.diagnostics

import okhttp3.Request
import org.imunav.core.net.Http
import org.imunav.core.net.LoopbackServer
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticRequestGateTest {
    private val owner = DiagnosticOwner("http://127.0.0.1", 1, "synthetic-A")

    @Test
    fun switchedDeletedAndLegacyAccountsCannotStartRequest() {
        val current = AtomicReference<DiagnosticOwner?>(owner)
        val gate = DiagnosticRequestGate(current::get) { true }
        val call = Http.client.newCall(Request.Builder().url("http://127.0.0.1:1").build())
        for (other in listOf(
            owner.copy(accountId = 2, syncIdentity = "synthetic-B"),
            owner.copy(syncIdentity = "recreated"),
            owner.copy(server = "https://example.invalid"),
            null,
        )) {
            current.set(other)
            assertFailsWith<DiagnosticOwnerChanged> { gate.execute(owner, call) {} }
            assertTrue(!call.isExecuted())
        }
    }

    @Test
    fun accountSwitchCancelsInFlightRequestAndNeverAcknowledgesResponse() {
        val current = AtomicReference<DiagnosticOwner?>(owner)
        val gate = DiagnosticRequestGate(current::get) { true }
        val accepted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val peer = workers.submit {
                server.accept().use { socket ->
                    accepted.countDown()
                    socket.soTimeout = 2000
                    socket.getInputStream().readBytes()
                }
            }
            val request = workers.submit<Boolean> {
                val call = Http.client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}").build())
                try {
                    gate.execute(owner, call) { true }
                } catch (_: IOException) {
                    false
                }
            }
            try {
                assertTrue(accepted.await(2, TimeUnit.SECONDS))
                current.set(owner.copy(accountId = 2, syncIdentity = "synthetic-B"))
                gate.cancelAll()
                assertEquals(false, request.get(2, TimeUnit.SECONDS))
                peer.get(2, TimeUnit.SECONDS)
            } finally {
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun ownerChangeDuringResponseProcessingRejectsAcknowledgement() {
        val current = AtomicReference<DiagnosticOwner?>(owner)
        val gate = DiagnosticRequestGate(current::get) { true }
        LoopbackServer({ socket ->
            LoopbackServer.readRequest(socket)
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        }, {}).use { server ->
            val call = Http.client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}").build())
            assertFailsWith<DiagnosticOwnerChanged> {
                gate.execute(owner, call) { response ->
                    response.body.string()
                    current.set(owner.copy(accountId = 2, syncIdentity = "synthetic-B"))
                }
            }
        }
    }
}
