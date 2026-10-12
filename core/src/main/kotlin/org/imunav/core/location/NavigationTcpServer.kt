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
    private val latest = AtomicReference<Publication?>()
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
    fun publish(sample: NavigationPositionSample?, mirror: NavigationMirrorPublication? = null) {
        if (sample == null) invalidations.incrementAndGet()
        latest.set(sample?.let { Publication(it, mirror) })
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
            if (clients.size >= MAX_CLIENTS || latest.get()?.sample?.fresh(clock()) != true) {
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
        val publication = latest.get()?.takeIf { it.sample.fresh(now) }
        if (publication == null) {
            clients.keys.toList().forEach(::remove)
            return
        }
        val sample = publication.sample
        val bytes = NavigationNmea.encode(sample).toByteArray(Charsets.US_ASCII)
        clients.toMap().forEach { (socket, client) ->
            try {
                readRequest(socket, client, publication.mirror != null)
                if (client.pending == null) queueNext(client, publication, bytes, now)
                writePending(socket, client, publication, now)
            } catch (_: IOException) {
                remove(socket)
            }
        }
    }

    private fun writePending(socket: SocketChannel, client: Client, publication: Publication, now: Long) {
        val buffer = client.pending ?: return
        if (now - client.pendingElapsedMs > NavigationPositionSample.MAX_AGE_MS || now - client.startedMs >= WRITE_TIMEOUT_MS) {
            remove(socket)
            return
        }
        socket.write(buffer)
        if (!buffer.hasRemaining()) {
            client.pending = null
            if (client.mirror && client.sentRoute != publication.mirror?.routeRevision) selector.wakeup()
        }
    }

    /** Only the fixed subscription line is accepted; a peer cannot grow the receive buffer. */
    private fun readRequest(socket: SocketChannel, client: Client, available: Boolean) {
        client.readProbe.clear()
        val count = socket.read(client.readProbe)
        if (count < 0) throw IOException("Receiver closed")
        client.readProbe.flip()
        while (client.readProbe.hasRemaining()) {
            val byte = client.readProbe.get().toInt().toChar()
            if (client.mirror || client.request.length >= NavigationMirrorEncoder.HANDSHAKE.length) throw IOException("Invalid receiver request")
            client.request.append(byte)
            if (!NavigationMirrorEncoder.HANDSHAKE.startsWith(client.request.toString())) throw IOException("Invalid receiver request")
            if (client.request.toString() == NavigationMirrorEncoder.HANDSHAKE && available) {
                client.mirror = true
                client.hello = true
                client.lastElapsedMs = -1L
            }
        }
    }

    /** Finish the current line before switching modes and interleave fresh states with route chunks. */
    private fun queueNext(client: Client, publication: Publication, nmea: ByteArray, now: Long) {
        val mirror = publication.mirror
        val bytes = when {
            client.hello -> {
                client.hello = false
                NavigationMirrorEncoder.HELLO.toByteArray(Charsets.UTF_8)
            }

            client.lastElapsedMs != publication.sample.elapsedMs -> {
                client.lastElapsedMs = publication.sample.elapsedMs
                if (client.mirror && mirror != null) mirror.stateLine else nmea
            }

            client.mirror && mirror != null && mirror.routeRevision != null && client.sentRoute != mirror.routeRevision -> {
                if (client.pendingRoute != mirror.routeRevision) {
                    client.pendingRoute = mirror.routeRevision
                    client.routeIndex = 0
                }
                mirror.routeFrames.getOrNull(client.routeIndex++)?.also {
                    if (client.routeIndex == mirror.routeFrames.size) client.sentRoute = mirror.routeRevision
                }
            }

            else -> null
        }
        if (bytes != null) {
            client.pending = ByteBuffer.wrap(bytes)
            client.startedMs = now
            client.pendingElapsedMs = publication.sample.elapsedMs
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
        var pendingElapsedMs = 0L
        val readProbe: ByteBuffer = ByteBuffer.allocate(64)
        val request = StringBuilder()
        var mirror = false
        var hello = false
        var pendingRoute: Long? = null
        var sentRoute: Long? = null
        var routeIndex = 0
    }

    private data class Publication(val sample: NavigationPositionSample, val mirror: NavigationMirrorPublication?)

    companion object {
        const val DEFAULT_PORT = 10110
        private const val MAX_CLIENTS = 4
        private const val MAX_ACCEPTS = 8 // Give existing receivers a freshness check even during connection floods.
        private const val SEND_BUFFER_BYTES = 4_096 // Limit positions buffered by the kernel ahead of a stalled receiver.
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val WATCHDOG_MS = 500L
    }
}
