package org.blinddriver.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.blinddriver.core.cells.CellCsv
import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellMerge
import org.blinddriver.core.cells.CellTower
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * In-memory tower store persisted as a gzip CSV (OpenCellID columns; `updated` = last change,
 * epoch seconds). Contributions of the same cell are merged, weighted by sample count.
 */
class CellStore(private val file: File?) {
    private class Entry(val tower: CellTower, val updatedS: Long)

    private val cells = ConcurrentHashMap<CellKey, Entry>()

    val size: Int get() = cells.size

    init {
        if (file != null && file.exists()) {
            file.inputStream().use { input ->
                CellCsv.read(input) { t -> cells[t.key] = Entry(t, 0) }
            }
            // Recover per-row timestamps (column 13 = updated).
            file.inputStream().use { input ->
                readUpdated(input)
            }
        }
    }

    private fun readUpdated(input: InputStream) {
        val stream = java.util.zip.GZIPInputStream(input)
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = org.blinddriver.core.cells.OpenCellIdCsv.parse(line) ?: continue
                val updated = line.split(',').getOrNull(12)?.toLongOrNull() ?: 0
                cells[t.key]?.let { cells[t.key] = Entry(it.tower, updated) }
            }
        }
    }

    /** Merge towers into the store; returns how many were accepted. */
    @Synchronized
    fun contribute(towers: List<CellTower>, nowS: Long = System.currentTimeMillis() / 1000): Int {
        var n = 0
        for (t in towers) {
            if (t.samples <= 0 || t.rangeM <= 0 || t.rangeM > 50_000) continue
            val existing = cells[t.key]?.tower
            cells[t.key] = Entry(if (existing == null) t else CellMerge.merge(existing, t), nowS)
            n++
        }
        return n
    }

    fun query(mccs: Set<Int>?, sinceS: Long): List<Pair<CellTower, Long>> =
        cells.values.filter { (mccs == null || it.tower.key.mcc in mccs) && it.updatedS >= sinceS }.map { it.tower to it.updatedS }

    @Synchronized
    fun save() {
        val target = file ?: return
        val tmp = File(target.parentFile ?: File("."), target.name + ".tmp")
        val snapshot = cells.values.toList()
        val updated = snapshot.associate { it.tower.key to it.updatedS }
        tmp.outputStream().use { out -> CellCsv.writeGzip(snapshot.map { it.tower }, out) { updated[it.key] ?: 0 } }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

/**
 * Tiny cell-sharing server (JDK HttpServer, no dependencies).
 *
 *  - `POST /v1/cells` — body: CSV or gzip CSV of towers; needs `Authorization: Bearer <key>` if an API key is set
 *  - `GET  /v1/cells.csv.gz?mcc=255,256&since=<epoch s>` — gzip CSV of merged towers (open)
 *  - `GET  /health`
 */
class CellServer(private val store: CellStore, private val apiKey: String?, port: Int) {
    private val http: HttpServer = HttpServer.create(InetSocketAddress(port), 0)
    val port: Int get() = http.address.port

    init {
        http.executor = Executors.newFixedThreadPool(4)
        http.createContext("/health") { ex -> respond(ex, 200, "text/plain", "ok ${store.size}\n".toByteArray()) }
        http.createContext("/v1/cells") { ex -> handleUpload(ex) }
        http.createContext("/v1/cells.csv.gz") { ex -> handleDownload(ex) }
    }

    fun start() = http.start()

    fun stop() = http.stop(0)

    private fun handleUpload(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "POST") return respond(ex, 405, "text/plain", "POST only\n".toByteArray())
            if (!apiKey.isNullOrBlank() && ex.requestHeaders.getFirst("Authorization") != "Bearer $apiKey") {
                return respond(ex, 401, "application/json", "{\"status\":\"error\",\"message\":\"UNAUTHORIZED\"}".toByteArray())
            }
            val towers = ArrayList<CellTower>()
            val body = LimitedInputStream(ex.requestBody, MAX_UPLOAD_BYTES)
            CellCsv.read(body) { towers += it }
            val accepted = store.contribute(towers)
            store.save()
            log("upload from ${ex.remoteAddress.address.hostAddress}: ${towers.size} rows, $accepted accepted, store=${store.size}")
            respond(ex, 200, "application/json", "{\"status\":\"ok\",\"accepted\":$accepted}".toByteArray())
        } catch (e: Exception) {
            respond(ex, 400, "application/json", "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "'")}\"}".toByteArray())
        }
    }

    private fun handleDownload(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "text/plain", "GET only\n".toByteArray())
        val params = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        val mccs = params["mcc"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
        val since = params["since"]?.toLongOrNull() ?: 0
        val rows = store.query(mccs, since)
        val updated = rows.associate { it.first.key to it.second }
        ex.responseHeaders.add("Content-Type", "application/gzip")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out -> CellCsv.writeGzip(rows.map { it.first }, out) { updated[it.key] ?: 0 } }
        log("download mcc=${mccs ?: "all"} since=$since: ${rows.size} rows")
    }

    private fun respond(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun log(msg: String) = println("[cells] $msg")

    private class LimitedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0 && ++count > limit) throw IllegalStateException("upload too large") }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also {
            if (it > 0) {
                count += it
                if (count > limit) throw IllegalStateException("upload too large")
            }
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
    }
}
