package org.imunav.core.location

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Nonblocking writes and one latest sample keep slow receivers entirely off the navigation thread. */
class NavigationTcpServer(address: InetAddress, port: Int = DEFAULT_PORT, private val clock: () -> Long, private val onReceivers: (Int) -> Unit = {}) : Closeable {
    init {
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress)) { "Private IPv4 required" }
        require(port in 0..65535) { "Invalid TCP port" }
    }

    private val selector = Selector.open()
    private val listener = ServerSocketChannel.open()
    private val latest = AtomicReference<NavigationPositionSample?>()
    private val invalidations = AtomicLong()
    private var lastInvalidation = 0L
    private val clients = mutableMapOf<SocketChannel, Client>()

    @Volatile private var running = true
    private val worker: Thread
    val port: Int get() = (listener.localAddress as InetSocketAddress).port

    init {
        try {
            listener.configureBlocking(false)
            listener.bind(InetSocketAddress(address, port))
            listener.register(selector, SelectionKey.OP_ACCEPT)
        } catch (failure: IOException) {
            listener.close()
            selector.close()
            throw failure
        }
        worker = Thread(::run, "navigation-nmea").apply { isDaemon = true }
        worker.start()
    }

    /** Replaces pending data; null invalidates all existing receiver connections. */
    fun publish(sample: NavigationPositionSample?) {
        if (sample == null) invalidations.incrementAndGet()
        latest.set(sample)
        selector.wakeup()
    }

    private fun run() {
        try {
            while (running) {
                selector.select(WATCHDOG_MS)
                if (!running) break
                selector.selectedKeys().clear()
                accept()
                writeLatest()
            }
        } catch (_: IOException) {
            // Closing the listener can interrupt accept; shutdown still releases every receiver.
        } finally {
            running = false
            clients.keys.toList().forEach(::remove)
            listener.close()
            selector.close()
        }
    }

    private fun accept() {
        repeat(MAX_ACCEPTS) {
            val socket = listener.accept() ?: return
            if (clients.size >= MAX_CLIENTS || latest.get()?.fresh(clock()) != true) {
                socket.close()
            } else {
                try {
                    socket.configureBlocking(false)
                    socket.socket().tcpNoDelay = true
                    socket.socket().sendBufferSize = SEND_BUFFER_BYTES
                    clients[socket] = Client()
                    onReceivers(clients.size)
                } catch (_: IOException) {
                    socket.close()
                }
            }
        }
    }

    private fun writeLatest() {
        val invalidation = invalidations.get()
        if (invalidation != lastInvalidation) {
            clients.keys.toList().forEach(::remove)
            lastInvalidation = invalidation
        }
        val now = clock()
        val sample = latest.get()?.takeIf { it.fresh(now) }
        if (sample == null) {
            clients.keys.toList().forEach(::remove)
            return
        }
        val bytes = NavigationNmea.encode(sample).toByteArray(Charsets.US_ASCII)
        clients.toMap().forEach { (socket, client) ->
            try {
                if (client.pending == null && client.lastElapsedMs != sample.elapsedMs) {
                    client.pending = ByteBuffer.wrap(bytes)
                    client.startedMs = now
                    client.lastElapsedMs = sample.elapsedMs
                }
                client.pending?.let { buffer ->
                    if (now - client.lastElapsedMs > NavigationPositionSample.MAX_AGE_MS || now - client.startedMs >= WRITE_TIMEOUT_MS) {
                        remove(socket)
                    } else {
                        socket.write(buffer)
                        if (!buffer.hasRemaining()) client.pending = null
                    }
                }
                // A closed peer is detected even if the car has not produced a new sample.
                if (socket.read(client.readProbe) < 0) remove(socket)
                client.readProbe.clear()
            } catch (_: IOException) {
                remove(socket)
            }
        }
    }

    private fun remove(socket: SocketChannel) {
        runCatching { socket.close() }
        if (clients.remove(socket) != null) onReceivers(clients.size)
    }

    override fun close() {
        running = false
        listener.close()
        selector.wakeup()
        if (Thread.currentThread() != worker) worker.join(WRITE_TIMEOUT_MS)
    }

    private class Client {
        var pending: ByteBuffer? = null
        var startedMs = 0L
        var lastElapsedMs = -1L
        val readProbe: ByteBuffer = ByteBuffer.allocate(1)
    }

    companion object {
        const val DEFAULT_PORT = 10110
        private const val MAX_CLIENTS = 4
        private const val MAX_ACCEPTS = 8 // Give existing receivers a freshness check even during connection floods.
        private const val SEND_BUFFER_BYTES = 4_096 // Limit positions buffered by the kernel ahead of a stalled receiver.
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val WATCHDOG_MS = 500L
    }
}
