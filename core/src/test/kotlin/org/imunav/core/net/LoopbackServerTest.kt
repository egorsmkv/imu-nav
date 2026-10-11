package org.imunav.core.net

import org.junit.Test
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackServerTest {
    @Test
    fun validRangeAndOversizedHeadersUseBoundedParser() {
        LoopbackServer({ socket ->
            val request = LoopbackServer.readRequest(socket)
            assertEquals("GET /tiles.pmtiles HTTP/1.1", request.line)
            assertEquals("bytes=1-3", request.range)
            socket.getOutputStream().write("ok".toByteArray())
        }, {}).use { server ->
            client(server).use { socket ->
                socket.getOutputStream().write("GET /tiles.pmtiles HTTP/1.1\r\nRange: bytes=1-3\r\n\r\n".toByteArray())
                assertEquals("ok", socket.getInputStream().readBytes().toString(Charsets.US_ASCII))
            }
            client(server).use { socket ->
                socket.getOutputStream().write(("GET /tiles.pmtiles HTTP/1.1\r\nX: " + "a".repeat(9000)).toByteArray())
                assertClosed(socket)
            }
        }
    }

    @Test
    fun slowHeadersHaveDeadlineFromAcceptanceAndShutdownClosesQueuedClients() {
        val started = CountDownLatch(1)
        LoopbackServer({ socket ->
            started.countDown()
            LoopbackServer.readRequest(socket)
        }, {}, workers = 1, queueSize = 1, timeoutMs = 300).use { server ->
            client(server).use { first ->
                assertTrue(started.await(2, TimeUnit.SECONDS))
                client(server).use { queued ->
                    assertEquals(-1, first.getInputStream().read())
                    assertEquals(-1, queued.getInputStream().read())
                }
            }
        }
    }

    @Test
    fun saturatedWorkersAndQueueRejectAdditionalConnection() {
        val started = CountDownLatch(1)
        LoopbackServer({ socket ->
            started.countDown()
            LoopbackServer.readRequest(socket)
        }, {}, workers = 1, queueSize = 1, timeoutMs = 5000).use { server ->
            client(server).use {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                client(server).use {
                    // TCP accepts connections in order: one is executing and one fills the waiting slot.
                    client(server).use { rejected -> assertClosed(rejected) }
                }
            }
        }
    }

    @Test
    fun shutdownClosesActiveConnection() {
        val started = CountDownLatch(1)
        LoopbackServer({ socket ->
            started.countDown()
            LoopbackServer.readRequest(socket)
        }, {}).use { server ->
            client(server).use { socket ->
                assertTrue(started.await(2, TimeUnit.SECONDS))
                server.close()
                assertClosed(socket)
            }
        }
    }

    @Test
    fun stalledResponseWritesCannotOccupyWorkerIndefinitely() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        LoopbackServer({ socket ->
            socket.sendBufferSize = 1024
            started.countDown()
            try {
                val bytes = ByteArray(64 * 1024)
                repeat(1024) { socket.getOutputStream().write(bytes) }
            } finally {
                finished.countDown()
            }
        }, {}, workers = 1, queueSize = 1, timeoutMs = 300).use { server ->
            client(server).use {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertTrue(finished.await(2, TimeUnit.SECONDS))
            }
        }
    }

    private fun assertClosed(socket: Socket) {
        try {
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: SocketException) {
            // Unread rejected bytes may cause TCP reset instead of an orderly EOF.
        }
    }

    private fun client(server: LoopbackServer) = Socket("127.0.0.1", server.port).apply { soTimeout = 2000 }
}
