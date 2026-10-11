package org.imunav.core.net

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One bounded HTTP request; binary responses are streamed separately. */
data class RangeRequest(val line: String, val range: String?)

/**
 * Bounds queued sockets and closes each at an acceptance-time deadline, including blocked response writes.
 * Only loopback is exposed. Rejected clients are closed immediately and shutdown releases every resource.
 */
class LoopbackServer(private val serve: (Socket) -> Unit, private val log: (String) -> Unit, workers: Int = 4, queueSize: Int = 4, private val timeoutMs: Long = 5_000) :
    Closeable {
    private val server = ServerSocket(0, workers + queueSize, InetAddress.getByName("127.0.0.1"))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "map-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val executor = ThreadPoolExecutor(
        workers,
        workers,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueSize),
        { task -> Thread(task, "map-range").apply { isDaemon = true } },
    )
    val port: Int get() = server.localPort

    init {
        require(timeoutMs > 0)
        Thread(::accept, "map-accept").apply { isDaemon = true }.start()
    }

    private fun accept() {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                connections.add(socket)
                val deadline = deadlines.schedule({ socket.close() }, timeoutMs, TimeUnit.MILLISECONDS)
                try {
                    executor.execute {
                        try {
                            socket.use(serve)
                        } catch (error: IOException) {
                            log("bundled_map_range_failed ${error.message}")
                        } finally {
                            deadline.cancel(false)
                            connections.remove(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    deadline.cancel(false)
                    connections.remove(socket)
                    socket.close()
                }
            } catch (error: IOException) {
                if (!server.isClosed) log("bundled_map_accept_failed ${error.message}")
            } catch (_: RejectedExecutionException) {
                // Shutdown may race with acceptance; close() also closes the accepted socket set.
                connections.forEach { runCatching { it.close() } }
            }
        }
    }

    override fun close() {
        server.close()
        connections.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        deadlines.shutdownNow()
    }

    companion object {
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_HEADER_LINES = 64

        /** Read CRLF-terminated ASCII headers within a total budget, rejecting duplicate range headers. */
        fun readRequest(socket: Socket): RangeRequest {
            val input = socket.getInputStream().buffered()
            var bytes = 0
            fun line(): String {
                val text = StringBuilder()
                while (++bytes <= MAX_HEADER_BYTES) {
                    val value = input.read()
                    if (value == '\r'.code) {
                        if (++bytes > MAX_HEADER_BYTES || input.read() != '\n'.code) throw IOException("Invalid HTTP header")
                        return text.toString()
                    }
                    if (value != '\t'.code && value !in 32..126) throw IOException("Invalid HTTP header")
                    text.append(value.toChar())
                }
                throw IOException("HTTP header too large")
            }
            val request = line()
            var range: String? = null
            repeat(MAX_HEADER_LINES) {
                val header = line()
                if (header.isEmpty()) return RangeRequest(request, range)
                if (header.startsWith("Range:", ignoreCase = true)) {
                    if (range != null) throw IOException("Duplicate range header")
                    range = header.substringAfter(':').trim()
                }
            }
            throw IOException("Too many HTTP headers")
        }
    }
}
