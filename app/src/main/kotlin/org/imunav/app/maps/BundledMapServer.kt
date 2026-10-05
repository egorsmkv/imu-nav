package org.imunav.app.maps

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Serves byte ranges from the uncompressed PMTiles APK asset on loopback. MapLibre's Android
 * asset reader returns the entire asset for a range request; its HTTP reader handles ranges.
 * This avoids an extra installed copy of a large regional map.
 */
internal class BundledMapServer(private val context: Context, private val log: (String) -> Unit) {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(WORKERS) { task ->
        Thread(task, "bundled-map-range").apply { isDaemon = true }
    }

    val tileUrl: String = "http://127.0.0.1:${server.localPort}/tiles.pmtiles"

    init {
        Thread(::accept, "bundled-map-accept").apply { isDaemon = true }.start()
    }

    /** Accept only local connections; each worker reads a bounded range from the APK descriptor. */
    private fun accept() {
        while (!server.isClosed) {
            try {
                val connection = server.accept()
                workers.execute { serve(connection) }
            } catch (error: IOException) {
                if (!server.isClosed) log("bundled_map_accept_failed ${error.message}")
            }
        }
    }

    /** Respond to one GET with a complete range response and then close the connection. */
    private fun serve(connection: Socket) {
        connection.use { socket ->
            try {
                socket.soTimeout = REQUEST_TIMEOUT_MS
                val (path, range) = readRequest(socket) ?: return
                if (path != "GET /tiles.pmtiles HTTP/1.1" && path != "GET /tiles.pmtiles HTTP/1.0") {
                    sendError(socket.getOutputStream(), "404 Not Found", "")
                } else {
                    sendAssetRange(socket.getOutputStream(), range)
                }
            } catch (error: IOException) {
                log("bundled_map_range_failed ${error.message}")
            }
        }
    }

    /** Parse the request without buffering the binary response in memory. */
    private fun readRequest(socket: Socket): Pair<String, String?>? {
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val path = input.readLine() ?: return null
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        return path to range
    }

    /** MapLibre requests small byte ranges; keep the gzip tile bytes intact. */
    private fun sendAssetRange(rawOutput: OutputStream, range: String?) {
        context.assets.openFd(ASSET_PATH).use { asset ->
            val total = asset.length
            val match = range?.let { RANGE.matchEntire(it) }
            val start = match?.groupValues?.get(1)?.toLongOrNull() ?: if (range == null) 0L else -1L
            val end = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (total - 1)
            if (start !in 0 until total || end !in start until total) {
                sendError(rawOutput, "416 Range Not Satisfiable", "Content-Range: bytes */$total\r\n")
                return
            }
            val output = rawOutput.buffered()
            val size = end - start + 1
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/$total\r\n"
            output.write(
                (
                    "HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n$contentRange" +
                        "Content-Length: $size\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            copyRange(asset, output, start, size)
            output.flush()
        }
    }

    /** Read from the APK file descriptor at the asset's offset, with a fixed-size buffer. */
    private fun copyRange(asset: AssetFileDescriptor, output: OutputStream, start: Long, size: Long) {
        FileInputStream(asset.fileDescriptor).channel.use { channel ->
            channel.position(asset.startOffset + start)
            val buffer = ByteArray(BUFFER_BYTES)
            var remaining = size
            while (remaining > 0) {
                val count = channel.read(java.nio.ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()))
                if (count < 1) throw IOException("bundled PMTiles asset ended early")
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun sendError(output: OutputStream, status: String, extraHeader: String) {
        output.write("HTTP/1.1 $status\r\n${extraHeader}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private companion object {
        const val ASSET_PATH = "map/tiles.pmtiles"
        val RANGE = Regex("bytes=(\\d+)-(\\d*)")
        const val BACKLOG = 8
        const val WORKERS = 4
        const val REQUEST_TIMEOUT_MS = 5_000
        const val BUFFER_BYTES = 64 * 1024
    }
}
