package org.blinddriver.core.cells

import org.blinddriver.core.geo.Geo
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

/** Writes towers in OpenCellID CSV format (the exchange format of the whole cell pipeline). */
object CellCsv {
    const val HEADER = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"

    fun format(t: CellTower, updatedEpochS: Long = 0): String = String.format(
        Locale.US,
        "%s,%d,%d,%d,%d,,%.7f,%.7f,%d,%d,1,%d,%d,",
        t.key.radio.name, t.key.mcc, t.key.mnc, t.key.area, t.key.cid, t.lon, t.lat, t.rangeM.toLong(), t.samples, updatedEpochS, updatedEpochS,
    )

    fun writeGzip(towers: Iterable<CellTower>, out: OutputStream, updatedEpochS: (CellTower) -> Long = { 0 }) {
        GZIPOutputStream(out).bufferedWriter().use { w ->
            w.write(HEADER)
            w.write("\n")
            for (t in towers) {
                w.write(format(t, updatedEpochS(t)))
                w.write("\n")
            }
        }
    }

    /** Stream-parse a CSV (gzip auto-detected); [onTower] is called per valid row. Returns rows read. */
    fun read(input: InputStream, onTower: (CellTower) -> Unit): Long {
        val buffered = input.buffered(1 shl 16)
        buffered.mark(2)
        val magic = (buffered.read() shl 8) or buffered.read()
        buffered.reset()
        val stream = if (magic == 0x1f8b) GZIPInputStream(buffered, 1 shl 16) else buffered
        var n = 0L
        BufferedReader(stream.reader(), 1 shl 16).useLines { lines ->
            for (line in lines) {
                val t = OpenCellIdCsv.parse(line) ?: continue
                onTower(t)
                n++
            }
        }
        return n
    }
}

/**
 * Combines observations of the same cell from different sources/devices: sample-weighted centroid,
 * range grown to cover both estimates.
 */
object CellMerge {
    fun merge(a: CellTower, b: CellTower): CellTower {
        require(a.key == b.key)
        val wa = max(a.samples, 1).toDouble()
        val wb = max(b.samples, 1).toDouble()
        val lat = (a.lat * wa + b.lat * wb) / (wa + wb)
        val lon = (a.lon * wa + b.lon * wb) / (wa + wb)
        val range = max(
            a.rangeM + Geo.distance(lat, lon, a.lat, a.lon),
            b.rangeM + Geo.distance(lat, lon, b.lat, b.lon),
        ).let { min(it, 50_000.0) }
        return CellTower(a.key, lat, lon, range, min(a.samples + b.samples, 1_000_000))
    }
}

/**
 * Client for a cell-sharing server (see the `server` module):
 *  - `POST {base}/v1/cells` — gzip CSV of towers learned on this device; returns `{"accepted":N}`
 *  - `GET  {base}/v1/cells.csv.gz?mcc=255&since=<epoch s>` — gzip CSV of merged towers
 * An optional API key is sent as `Authorization: Bearer <key>`; [deviceId] (random per install) lets
 * the server weigh contributions per device and rate-limit.
 * Only tower positions are exchanged — never the device's own track.
 */
class CellSyncClient(baseUrl: String, private val apiKey: String? = null, private val deviceId: String? = null) {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "server URL must start with http:// or https://" }
    }

    fun upload(towers: List<CellTower>): Int {
        if (towers.isEmpty()) return 0
        val body = ByteArrayOutputStream().also { CellCsv.writeGzip(towers, it) }.toByteArray()
        val conn = open("$base/v1/cells", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/csv")
        conn.setRequestProperty("Content-Encoding", "gzip")
        conn.setFixedLengthStreamingMode(body.size)
        try {
            conn.outputStream.use { it.write(body) }
            val text = readBody(conn)
            return Regex("\"accepted\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: towers.size
        } finally {
            conn.disconnect()
        }
    }

    /** Download towers changed since [sinceEpochS] for the given country codes. */
    fun download(mccs: Collection<Int>, sinceEpochS: Long, onTower: (CellTower) -> Unit): Long {
        val query = "mcc=${mccs.joinToString(",")}&since=$sinceEpochS"
        val conn = open("$base/v1/cells.csv.gz?$query", "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("sync server HTTP $code: ${readError(conn)}")
            return conn.inputStream.use { CellCsv.read(it, onTower) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "blind-driver-opensource/0.1")
        apiKey?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer ${it.trim()}") }
        deviceId?.let { conn.setRequestProperty("X-Device-Id", it) }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("sync server HTTP $code: ${readError(conn)}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun readError(conn: HttpURLConnection): String = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) }.getOrNull().orEmpty()
}

/**
 * An InputStream over HTTP that transparently reconnects with a `Range` header after network
 * errors, so multi-gigabyte downloads survive Wi-Fi hiccups. Requires server byte-range support.
 */
class ResumableHttpInputStream(private val url: String, private val maxRetries: Int = 20, private val onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> }) :
    InputStream() {
    private var conn: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var position = 0L
    var total = -1L
        private set
    private var retries = 0

    private fun connect() {
        close()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "blind-driver-opensource/0.1")
        if (position > 0) c.setRequestProperty("Range", "bytes=$position-")
        val code = c.responseCode
        when {
            position > 0 && code == 206 -> Unit
            position == 0L && code in 200..299 -> total = c.contentLengthLong
            position > 0 && code == 200 -> throw IOException("server does not support resuming")
            else -> throw IOException("HTTP $code")
        }
        conn = c
        stream = c.inputStream.buffered(1 shl 16)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            try {
                if (stream == null) connect()
                val n = stream!!.read(b, off, len)
                if (n < 0) {
                    if (total > 0 && position < total) throw IOException("connection closed early at $position/$total")
                    return -1
                }
                position += n
                retries = 0
                onProgress(position, total)
                return n
            } catch (e: IOException) {
                if (++retries > maxRetries || e.message?.contains("resuming") == true) throw e
                close()
                Thread.sleep(minOf(30_000L, 1000L * retries))
            }
        }
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { conn?.disconnect() }
        stream = null
        conn = null
    }
}
